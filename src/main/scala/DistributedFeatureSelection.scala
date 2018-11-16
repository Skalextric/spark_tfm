import java.util

import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.ml.classification._
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator
import org.apache.spark.ml.feature.{MinMaxScaler, OneHotEncoder, StringIndexer, VectorAssembler}
import org.apache.spark.ml.{Pipeline, PipelineStage}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.rogach.scallop.{ScallopConf, ScallopOption}
import weka.core.{Attribute, Instances}
import weka.filters.Filter

import scala.collection.mutable
import scala.util.Random


object DistributedFeatureSelection {


  def main(args: Array[String]): Unit = {

    //Argument parser
    val opts = new ScallopConf(args) {
      banner("\nUsage of this program example: -d connect-4.csv -p 5 CFS,IG,CF  F1,F2 \n\n")
      val dataset: ScallopOption[String] = opt[String]("dataset", required = true, descr = "Dataset to use in CSV format / Class must be last or first column")
      val test_dataset: ScallopOption[String] = opt[String]("test dataset", descr = "Test dataset to use in CSV format / Class must be last or first column")
      val partType: ScallopOption[Boolean] = toggle("vertical", default = Some(false), descrYes = "Vertical partitioning / Default Horizontal")
      val overlap: ScallopOption[Double] = opt[Double]("overlap", default = Some(0.0), descr = "Overlap")
      val class_index: ScallopOption[Boolean] = toggle("first", noshort = true, default = Some(false), descrYes = "Required if class is first column")
      val numParts: ScallopOption[Int] = opt[Int]("partitions", validate = 0 < _, descr = "Num of partitions", required = true)
      val parallelism: ScallopOption[Int] = opt[Int]("parallelism", default = Some(100), validate = 0 < _, descr = "Num of RDDpartitions", required = true)
      val alpha: ScallopOption[Double] = opt[Double]("alpha", descr = "Aplha Value for threshold computation / Default 0.75", validate = { x => 0 <= x && x <= 1 }, default = Some(0.75))
      val fs_algorithms: ScallopOption[String] = opt[String](required = true, default = Some("CFS,IG,RF"), descr = "List of feature selection algorithm")
      val complexity_measure: ScallopOption[String] = opt[String](name = "comp_measure", required = true, default = Some("F1,F2"), descr = "List of complexity measures")
      val trainKNN: ScallopOption[Boolean] = toggle("trainKNN", default = Some(true), descrYes = "Wether or not use KNN to evalute models", prefix = "not")

      verify()
    }

    val start_time = System.currentTimeMillis()
    val ss = SparkSession.builder().appName("distributed_feature_selection")
//      .config("spark.sql.codegen.wholeStage", false)
//      .master("local[*]")
      .getOrCreate()
    val sc = ss.sparkContext

    sc.setLogLevel("ERROR")

    val unfiltered_rdd = parse_RDD(sc.textFile(opts.dataset(), opts.parallelism()), ',', opts.class_index())

    //Filter classes with only 1 example
    val one_sample_key = unfiltered_rdd.map(x => (x.last, x)).countByKey().filter(_._2 < 1).keySet
    val original_rdd = unfiltered_rdd.filter(x => !one_sample_key.contains(x.last))

    val attributes = createAttributesMap(original_rdd, sc)
    val br_attributes = sc.broadcast(attributes)
    val (train_rdd, test_rdd) = splitTrainTestRDD(br_attributes, original_rdd, opts.test_dataset.toOption, opts.class_index(), sc)
    train_rdd.cache()
    test_rdd.cache()

    val fs_algorithms = opts.fs_algorithms().split(",")
    val comp_measures = opts.complexity_measure().split(",")

    var cfs_features_selected = 1

    if (opts.partType()) println(s"*****Using vertical partitioning with ${opts.overlap() * 100}% overlap*****")
    else println(s"*****Using horizontal partitioning*****")
    println(s"*****Number of partitions: ${opts.numParts()}*****")


    for (fsa <- fs_algorithms) {

      /** Here we get the votes vector **/
      //val (votes, times, cfs_selected) = getVotesVector(train_rdd, br_attributes, opts.numParts(), opts.partType(), opts.overlap(), "CFS", cfs_features_selected, transposed_rdd, sc)
      val start_votes_time = System.currentTimeMillis()
      val (votes, times, cfs_selected) = getVotesVector(train_rdd, br_attributes, opts.numParts(), opts.partType(), opts.overlap(), "CFS", cfs_features_selected, sc)
      println(s"\nTime $fsa computation stats:${times.stats()}. Votes computation time ${System.currentTimeMillis() - start_votes_time}")

      if (fsa == "CFS") cfs_features_selected = (cfs_selected.sum() / cfs_selected.count()).toInt

      for (compmeasure <- comp_measures) {

        val start_sub_time = System.currentTimeMillis()

        println(s"*****Using $fsa algorithm with $compmeasure as complexity measure*****")

        val globalCompyMeasure = compmeasure match {
          case "F1" => fisherRatio
          case "F2" => f2
          case _ => zeroGlobal
        }


        val classifier = compmeasure match {
          case "SVM" =>
            Some(new OneVsRest().setClassifier(new LinearSVC()))
          case "KNN" =>
            Some(new KNNClassifier().setK(1))
          case "DT" =>
            Some(new DecisionTreeClassifier())
          case "NB" =>
            Some(new NaiveBayes())
          case _ =>
            None
        }

        /** Here we get the selected features **/
        val features = computeThreshold(train_rdd, votes, opts.alpha(), classifier, br_attributes, opts.partType(),
          opts.numParts(), 5, globalCompyMeasure, ss)

        println(s"Number of features is ${features.count() - 1}")
        println(s"Threshold computation time is ${System.currentTimeMillis() - start_sub_time}")


        if (fsa == "CFS") {
          cfs_features_selected = features.count().toInt
        }

        /** Here we evaluate several algorithms with the selected features **/
        val train_dataframe = createDataFrameFromFeatures(train_rdd, features, br_attributes, ss)
        val test_dataframe = createDataFrameFromFeatures(test_rdd, features, br_attributes, ss)
        val evaluation_time = System.currentTimeMillis()
        evaluateFeatures(train_dataframe, test_dataframe, br_attributes, features, sc, opts.trainKNN.toOption.get)
        println(s"Evaluation time is ${System.currentTimeMillis() - evaluation_time}")
        println("\n\n")

      }


    }

    println(s"Total script time is ${System.currentTimeMillis() - start_time}")
    println(s"TrainTest samples: ${train_rdd.count()} DataTest samples:${test_rdd.count()}")

  }


  def splitTrainTestRDD(br_attributes: Broadcast[Map[Int, (Option[Set[String]], String)]],
                        original_rdd: RDD[Array[String]], test_file: Option[String],
                        class_first_column: Boolean, sc: SparkContext): (RDD[Array[String]], RDD[Array[String]]) = {


    var train_rdd = sc.emptyRDD[Array[String]]
    var test_rdd = sc.emptyRDD[Array[String]]

    if (test_file.isDefined) {
      train_rdd = original_rdd
      test_rdd = parse_RDD(sc.textFile(test_file.get), ',', class_first_column)
    } else {
      val classes = br_attributes.value(br_attributes.value.size - 1)._1.get
      var partitioned = sc.emptyRDD[(Long, Array[String])]
      classes.foreach(_class => {
        partitioned ++= original_rdd.filter(_.last == _class).zipWithIndex().map { case (row: Array[String], index: Long) => (index % 3, row) }
      })
      train_rdd = partitioned.filter(x => x._1 == 1 || x._1 == 2).map(_._2)
      test_rdd = partitioned.filter(_._1 == 0).map(_._2)
    }
    println(s"Number of features detected: ${train_rdd.first().length - 1}")
    (train_rdd, test_rdd)
  }

  def createAttributesMap(rdd: RDD[Array[String]], sc: SparkContext): Map[Int, (Option[Set[String]], String)] = {

    /** *****************************
      * Creation of attributes maps
      * *****************************/

    val sample = rdd.take(1)(0).zipWithIndex
    val nominalAttributes = sample.dropRight(1).filter(tuple => parseNumeric(tuple._1).isEmpty).map(_._2) :+ (sample.length - 1)

    val uniques_nominal_values = rdd.flatMap(_.zipWithIndex).filter {
      case (_, index) => nominalAttributes.contains(index)
    }.map(tuple => (tuple._2, tuple._1))
      .combineByKey((value: String) => mutable.Set[String](value),
        (set: mutable.Set[String], new_value: String) => set += new_value,
        (set1: mutable.Set[String], set2: mutable.Set[String]) => set1 ++= set2).map(x => (x._1, Some(x._2.toSet))).collectAsMap()

    sample.map(tuple => {
      if (tuple._2 == (sample.length - 1)) tuple._2 -> (uniques_nominal_values.getOrElse(tuple._2, None), "class")
      else tuple._2 -> (uniques_nominal_values.getOrElse(tuple._2, None), "att_" + tuple._2.toString)
    }).toMap
  }

  def getVotesVector(rdd: RDD[Array[String]], br_attributes: Broadcast[Map[Int, (Option[Set[String]], String)]],
                     numParts: Int, vertical: Boolean, overlap: Double, filter: String, ranking_features: Int,
                     sc: SparkContext): (RDD[(String, Int)], RDD[Long], RDD[Int]) = {

    /** **************************
      * Getting the Votes vector.
      * **************************/

    val rounds = 5
    var times = sc.emptyRDD[Long]
    var cfs_selected = sc.emptyRDD[Int]
    var x = 0
    //    val rdd = old_rdd.repartition(100)
    val votes = {
      var sub_votes = sc.emptyRDD[(String, Int)]
      if (vertical) {
        while (x < rounds) {
          val result = verticalPartitioningFeatureSelection(sc, rdd,
            br_attributes, numParts, filter, overlap, ranking_features)
          sub_votes = sub_votes ++ result.map(x => (x._1, x._2._1))
          times ++= result.map(x => x._2._2)
          if (filter == "CFS") {
            cfs_selected ++= result.map(x => x._2._3)
          }
          x += 1
        }

      } else {
        val (schema, class_schema_index) = WekaWrapper.attributesSchema(br_attributes.value)
        val br_schema = sc.broadcast(schema)
        while (x < rounds) {
          val result = horizontalPartitioningFeatureSelection(sc, rdd,
            br_attributes, numParts, filter, br_schema, class_schema_index, ranking_features)
          sub_votes = sub_votes ++ result.map(x => (x._1, x._2._1))
          times ++= result.map(x => x._2._2)
          if (filter == "CFS") {
            cfs_selected ++= result.map(x => x._2._3)
          }
          x += 1

        }
        br_schema.unpersist()
      }
      sub_votes.reduceByKey(_ + _)
    }

    (votes, times, cfs_selected)
  }

  def computeThreshold(rdd: RDD[Array[String]], votes: RDD[(String, Int)], alpha_value: Double, classifier: Option[PipelineStage],
                       br_attributes: Broadcast[Map[Int, (Option[Set[String]], String)]],
                       vertical: Boolean, numParts: Int, rounds: Int = 5,
                       globalComplexityMeasure: DistributedFeatureSelection.complexityMeasure,
                       ss: SparkSession): RDD[String] = {

    val sc = ss.sparkContext
    /** ******************************************
      * Computing 'Selection Features Threshold'
      * ******************************************/

    val votes_length = votes.count()
    val avg_votes = votes.map(_._2).sum / votes_length
    val std_votes = math.sqrt(votes.map(votes => math.pow(votes._2 - avg_votes, 2)).sum / votes_length)
    val minVote = if (vertical) (avg_votes - std_votes).toInt else (avg_votes - (std_votes / 2)).toInt
    val maxVote = if (vertical) (avg_votes + std_votes).toInt else (avg_votes + (std_votes / 2)).toInt


    //We get the features that aren't in the votes set. That means features -> Votes = 0
    // ****Class column included****
    //    val selected_features_0_votes = sc.parallelize(br_attributes.value.map(_._2._2).filter(!votes.map(_._1).collect().contains(_)).toSeq)
    val aux = sc.parallelize(br_attributes.value.values.map(x => (x._2, 0)).toList).union(votes)
    val selected_features_0_votes = aux.reduceByKey(_ + _).filter(_._2 == 0).map(_._1)


    val alpha = alpha_value
    var e_v = collection.mutable.ArrayBuffer[(Int, Double)]()
    var compMeasure = 0.0
    var selected_features_aux = 0
    val step = if (vertical) 1 else 5
    for (a <- minVote to maxVote by step) {

      // We add votes below Threshold value
      val selected_features = selected_features_0_votes ++ votes.filter(_._2 < a).map(_._1)
      val selected_features_indexes = selected_features.map(value => if (value != "class") value.substring(4).toInt else br_attributes.value.size - 1).collect()

      if (selected_features_indexes.length > 1 && selected_features_aux != selected_features_indexes.length) {
        println(s"Number of selected features: ${selected_features_indexes.length - 1}")
        selected_features_aux = selected_features_indexes.length
        val selected_features_rdd = rdd.map(row => row.zipWithIndex.filter( tuple => selected_features_indexes.contains(tuple._2)))

        val retained_feat_percent = (selected_features_indexes.length.toDouble / (br_attributes.value.size - 1)) * 100


        val start_meassure_time = System.currentTimeMillis()
        if (classifier.isDefined) {
          //          val (pipeline_stages, columns_to_cast) = createPipeline(selected_features, br_attributes, sc)
          //          val casted_dataframe = castDFToDouble(selected_features_dataframe, columns_to_cast)
          //          val pipeline = new Pipeline().setStages(pipeline_stages :+ classifier.get).fit(casted_dataframe)
          //          val evaluator = new MulticlassClassificationEvaluator().setLabelCol("label")
          //            .setPredictionCol("prediction").setMetricName("accuracy")
          //          compMeasure = 1 - evaluator.evaluate(pipeline.transform(casted_dataframe))
        } else {
          compMeasure = globalComplexityMeasure.compute(selected_features_rdd, br_attributes, sc)
        }
        val sub_e_v = (a, alpha * compMeasure + (1 - alpha) * retained_feat_percent)
        e_v += sub_e_v
        println(s"Time to compute complexity measure ${System.currentTimeMillis() - start_meassure_time} EV: $sub_e_v")

      }
    }
    val selected_threshold = e_v.minBy(_._2)._1
    println(s"Threshold $selected_threshold")
    val features = selected_features_0_votes ++ votes.filter(_._2 < selected_threshold).map(_._1)
    features
  }

  def evaluateFeatures(train_dataframe: DataFrame, test_dataframe: DataFrame,
                       br_attributes: Broadcast[Map[Int, (Option[Set[String]], String)]],
                       features: RDD[String],
                       sc: SparkContext, train_knn: Boolean): Unit = {

    /** ******************************************
      * Evaluate Models With Selected Features
      * ******************************************/

    val (pipeline_stages, columns_to_cast) = createPipeline(features, br_attributes, sc)

    val casted_train_dataframe = castDFToDouble(train_dataframe, columns_to_cast)
    val casted_test_dataframe = castDFToDouble(test_dataframe, columns_to_cast)

    val fittedpipeline = new Pipeline().setStages(pipeline_stages).fit(casted_train_dataframe.union(casted_test_dataframe))

    val transformed_train_dataset = fittedpipeline.transform(casted_train_dataframe)
    transformed_train_dataset.cache()
    val transformed_test_dataset = fittedpipeline.transform(casted_test_dataframe)
    transformed_test_dataset.cache()


    val evaluator = new MulticlassClassificationEvaluator().setLabelCol("label")
      .setPredictionCol("prediction").setMetricName("accuracy")


    Seq(
      ("SMV", new OneVsRest().setClassifier(new LinearSVC())),
      ("Decision Tree", new DecisionTreeClassifier()),
      ("Naive Bayes", new NaiveBayes()),
      ("KNN", new KNNClassifier().setTopTreeSize(transformed_train_dataset.count().toInt / 500 + 1).setK(1))

    )
      .foreach {

        case (name, classi) =>
          if (name != "KNN") {
            val accuracy = evaluator.evaluate(classi.fit(transformed_train_dataset).transform(transformed_test_dataset))
            println(s"Accuracy for $name is $accuracy")
          } else {
            if (train_knn) {
              val accuracy = evaluator.evaluate(classi.fit(transformed_train_dataset).transform(transformed_test_dataset))
              println(s"Accuracy for $name is $accuracy")
            }
          }

      }

  }

  /** ***********************
    * Partitioning functions
    * ************************/

  def horizontalPartitioningFeatureSelection(sc: SparkContext, input: RDD[Array[String]],
                                             br_attributes: Broadcast[Map[Int, (Option[Set[String]], String)]],
                                             numParts: Int, filter: String,
                                             br_attributes_schema: Broadcast[util.ArrayList[Attribute]],
                                             class_schema_index: Int, ranking_features: Int): RDD[(String, (Int, Long, Int))] = {

    val classes = br_attributes.value(br_attributes.value.size - 1)._1.get
    val acum_classes = classes.toSeq.map(class_ => class_ -> sc.longAccumulator(class_)).toMap

    input.map(row => {
      acum_classes(row.last).add(1)
      (acum_classes(row.last).value % numParts, row)
    })
      .combineByKey(
        (row: Array[String]) => {
          val data = new Instances("Rel", br_attributes_schema.value, 0)
          data.setClassIndex(class_schema_index)
          WekaWrapper.addRowToInstances(data, br_attributes.value, br_attributes_schema.value, row)
        },
        (inst: Instances, row: Array[String]) => WekaWrapper.addRowToInstances(inst, br_attributes.value, br_attributes_schema.value, row),
        (inst1: Instances, inst2: Instances) => WekaWrapper.mergeInstances(inst1, inst2)
      )
      .flatMap {
        case (_, inst) =>
          val start_time = System.currentTimeMillis()
          val filtered_data = Filter.useFilter(inst, WekaWrapper.filterAttributes(inst, filter, ranking_features))
          val time = System.currentTimeMillis() - start_time
          val selected_attributes = WekaWrapper.getAttributes(filtered_data)
          (br_attributes.value.values.map(_._2).toSet.diff(selected_attributes) - br_attributes.value(br_attributes.value.size - 1)._2).map((_, (1, time, filtered_data.numAttributes())))
      }.reduceByKey((t1, t2) => (t1._1 + t2._1, math.max(t1._2, t2._2), math.max(t1._3, t2._3)))


  }


  def verticalPartitioningFeatureSelection(sc: SparkContext, input: RDD[Array[String]], br_attributes: Broadcast[Map[Int, (Option[Set[String]], String)]],
                                           numParts: Int, filter: String, overlap: Double = 0, ranking_features: Int): RDD[(String, (Int, Long, Int))] = {

    val class_index = br_attributes.value.size - 1
    val items = Math.ceil((br_attributes.value.size - 1) / numParts.toDouble).toInt
    val splittedAttributes = Random.shuffle(br_attributes.value.filter(_._2._2 != "class").keys.toSeq).grouped(items).toList

    val per_partition_schema = sc.parallelize(splittedAttributes, 50).zipWithIndex.map({ case (attributes, index) =>
      val schema = WekaWrapper.attributesSchema(br_attributes.value.filterKeys((attributes :+ class_index).contains(_)))
      (index, schema._1)
    }).collectAsMap()

    val br_per_partition_schemas = sc.broadcast(per_partition_schema)
    val br_splittedAttributes = sc.broadcast(splittedAttributes)

    input.flatMap(row => {
      br_splittedAttributes.value.zipWithIndex.map(tuple => (tuple._2, (tuple._2, (tuple._1 :+ class_index collect row).to[Array])))
    })
      .combineByKey(
        (tuple: (Int, Array[String])) => {
          val data = new Instances("Rel", br_per_partition_schemas.value(tuple._1), 0)
          data.setClassIndex(br_per_partition_schemas.value(tuple._1).size - 1)
          WekaWrapper.addRowToInstances(data, br_attributes.value, br_per_partition_schemas.value(tuple._1), tuple._2)
        },
        (inst: Instances, tuple: (Int, Array[String])) => WekaWrapper.addRowToInstances(inst, br_attributes.value, br_per_partition_schemas.value(tuple._1), tuple._2),
        (inst1: Instances, inst2: Instances) => WekaWrapper.mergeInstances(inst1, inst2)
      )
      .repartition(if (numParts < 200) numParts else 200)
      .flatMap {
        case (_, inst) =>
          val start_time = System.currentTimeMillis()
          val filtered_data = Filter.useFilter(inst, WekaWrapper.filterAttributes(inst, filter, ranking_features))
          val time = System.currentTimeMillis() - start_time
          val selected_attributes = WekaWrapper.getAttributes(filtered_data)
          (br_attributes.value.values.map(_._2).toSet.diff(selected_attributes) - br_attributes.value(br_attributes.value.size - 1)._2).map((_, (1, time, filtered_data.numAttributes())))

      }.reduceByKey((t1, t2) => (t1._1 + t2._1, math.max(t1._2, t2._2), math.max(t1._3, t2._3)))
  }


  /** *******************
    * Auxiliar Functions
    * ********************/


  def parse_RDD(rdd: RDD[String], sep: Char, class_first_column: Boolean): RDD[Array[String]] = rdd.map((x: String) => {
    if (class_first_column) {
      val result = x.split(sep)
      result.drop(1) :+ result(0)
    } else x.split(sep)
  })

  def createDataFrameFromFeatures(rdd: RDD[Array[String]], selected_features: RDD[String], br_attributes: Broadcast[Map[Int, (Option[Set[String]], String)]], ss: SparkSession): DataFrame = {
    val features = selected_features.collect()
    val selected_features_indexes = features.map(value => if (value != "class") value.substring(4).toInt else br_attributes.value.size - 1)
    val selected_features_rdd = rdd.map(row => row.zipWithIndex.filter { case (_, index) => selected_features_indexes.contains(index) })
    val struct = true
    val schema = StructType(features.sortBy(x => if (x != "class") x.substring(4).toInt else br_attributes.value.size).map(name => StructField(name, StringType, struct)))
    ss.createDataFrame(selected_features_rdd.map(row => Row.fromSeq(row.map(_._1))), schema = schema)

  }

  def parseNumeric(s: String): Option[Double] = {
    try {
      Some(s.toDouble)
    } catch {
      case _: Exception => None
    }
  }

  def transposeRDD(rdd: RDD[Array[String]]): RDD[(Int, Seq[String])] = {
    val columnAndRow = rdd.zipWithIndex.flatMap {
      case (row, rowIndex) => row.toSeq.zipWithIndex.map {
        case (element, columnIndex) => columnIndex -> (rowIndex, element)
      }
    }
    columnAndRow.groupByKey.sortByKey().map {
      case (columnIndex, rowIterable) => (columnIndex, rowIterable.toSeq.sortBy(_._1).map(_._2))
    }
  }

  def transposeRDDRow(rdd: RDD[Row]): RDD[(Int, Seq[String])] = {
    val columnAndRow = rdd.zipWithIndex.flatMap {
      case (row, rowIndex) => row.toSeq.zipWithIndex.map {
        case (element, columnIndex) => columnIndex -> (rowIndex, element)
      }
    }
    columnAndRow.groupByKey.sortByKey().map { case (columnIndex, rowIterable) => (columnIndex, rowIterable.toSeq.sortBy(_._1).map(_._2.toString)) }

  }

  def castDFToDouble(df: DataFrame, columns: Array[String]): DataFrame = {
    //Cast to double of columns that aren't categorical
    df.select(df.columns.map { c =>
      if (columns.contains(c)) {
        col(c).cast("Double")
      } else {
        col(c)
      }
    }: _*)

  }

  def createPipeline(columns: RDD[String],
                     attributes: Broadcast[Map[Int, (Option[Set[String]], String)]],
                     sc: SparkContext
                    ): (Array[PipelineStage], Array[String]) = {

    val class_index = attributes.value.size - 1
    //Mllib needs two special columns [features] and [label] to work. We have to assemble the columns we selected
    val double_columns_to_assemble: RDD[String] = columns.filter(cname => {
      cname != "class" && attributes.value(cname.substring(4).toInt)._1.isEmpty
    })

    // Get categorical columns
    val categorical_columns_filter = columns.filter(cname => {
      cname != "class" && attributes.value(cname.substring(4).toInt)._1.isDefined
    })

    if (categorical_columns_filter.count > 0) {
      val stages_columns =
        categorical_columns_filter.map {
          cname =>
            val st_indexer = new StringIndexer().setInputCol(cname).setOutputCol(s"${cname}_index")
            (Array(st_indexer, new OneHotEncoder().setInputCol(st_indexer.getOutputCol).setOutputCol(s"${cname}_vect")), Array(s"${cname}_vect"))
        }.reduce((tuple1, tuple2) => (tuple1._1 ++ tuple2._1, tuple1._2 ++ tuple2._2))


      val columns_to_assemble: Array[String] = double_columns_to_assemble.collect() ++ stages_columns._2

      //Creation of pipeline // Transform class column from categorical to index //Assemble features

      val pipeline: Array[PipelineStage] = stages_columns._1 ++
        Array(new StringIndexer().setInputCol(attributes.value(class_index)._2).setOutputCol("label"),
          new VectorAssembler().setInputCols(columns_to_assemble).setOutputCol("noscaledfeatures"),
          new MinMaxScaler().setInputCol("noscaledfeatures").setOutputCol("features")
        )

      (pipeline, columns_to_assemble)

    } else {

      val columns_to_assemble: Array[String] = double_columns_to_assemble.collect()
      //Assemble features
      val pipeline: Array[PipelineStage] = Array(new StringIndexer().setInputCol(attributes.value(class_index)._2).setOutputCol("label"),
        new VectorAssembler().setInputCols(columns_to_assemble).setOutputCol("noscaledfeatures"),
        new MinMaxScaler().setInputCol("noscaledfeatures").setOutputCol("features")
      )

      (pipeline, columns_to_assemble)

    }


  }

  /** ********************
    * Complexity measures
    * *********************/
  class complexityMeasure {

    def compute(rdd: RDD[Array[(String, Int)]], br_attributes: Broadcast[Map[Int, (Option[Set[String]], String)]], sc: SparkContext): Double = {
      0.0
    }
  }

  object zeroGlobal extends complexityMeasure {

  }

  object fisherRatio extends complexityMeasure {
    var count: Long = 0
    var countbyKeyMap: collection.Map[String, Long] = Map()
    var proportions: Map[String, Double] = Map()

    override def compute(rdd: RDD[Array[(String, Int)]], br_attributes: Broadcast[Map[Int, (Option[Set[String]], String)]], sc: SparkContext): Double = {
      var result: Double = 9999999

      if (rdd.first().length > 3) {

        val processed_rdd = rdd.map(
          x => (x.last._1, x.dropRight(1).flatMap(
            y =>
              if (br_attributes.value(y._2)._1.isDefined) {
                br_attributes.value(y._2)._1.get.toSeq.sorted.map(z => if (z == y._1) 1.0 else 0.0)
              } else {
                Seq(y._1.toDouble)
              })
          )
        )


        if (this.proportions.isEmpty) {
          this.countbyKeyMap = processed_rdd.countByKey()

          this.count = rdd.count()
          this.proportions = this.countbyKeyMap.map(tuple => (tuple._1, tuple._2.toDouble / count)).toMap
        }

        val br_countbyKeyMap = sc.broadcast(this.countbyKeyMap)

        val meanMap = processed_rdd
          .reduceByKey((array1, array2) => array1.zip(array2).map(x => x._1 + x._2))
          .map(x => (x._1, x._2.map(_ / br_countbyKeyMap.value(x._1)))).collectAsMap()

        val varMap = processed_rdd
          .map(tuple => (tuple._1, tuple._2.zip(meanMap(tuple._1)).map(x => scala.math.pow(x._1 - x._2, 2))))
          .reduceByKey((array1, array2) => array1.zip(array2).map(x => x._1 + x._2))
          .map(x => (x._1, x._2.map(_ / br_countbyKeyMap.value(x._1)))).collectAsMap()

        br_countbyKeyMap.unpersist()

        val classes = br_attributes.value(br_attributes.value.size - 1)._1.get

        val f_feats = processed_rdd.first()._2.zipWithIndex.map(_._2).map(index => {

          var sumMean: Double = 0
          var sumVar: Double = 0.001
          var new_classes = classes

          classes.foreach(class_name => {

            val meanC = meanMap(class_name)(index)
            new_classes = new_classes.drop(1)

            new_classes.foreach(class2_name => {
              val meanK = meanMap(class2_name)(index)
              sumMean += scala.math.pow(meanC - meanK, 2) * this.proportions(class2_name) * this.proportions(class_name)
            })
            sumVar += varMap(class_name)(index)
          })

          sumMean / sumVar

        })

        result = 1 / f_feats.max


      }

      result

    }

  }



    object f2 extends complexityMeasure {

      override def compute(rdd: RDD[Array[(String, Int)]], br_attributes: Broadcast[Map[Int, (Option[Set[String]], String)]], sc: SparkContext): Double = {

        val classes = br_attributes.value(br_attributes.value.size - 1)._1.get
        val processed_rdd = rdd.map(
          x => (x.last._1, x.dropRight(1).flatMap(
            y =>
              if (br_attributes.value(y._2)._1.isDefined) {
                br_attributes.value(y._2)._1.get.toSeq.sorted.map(z => if (z == y._1) 1.0 else 0.0)
              } else {
                Seq(y._1.toDouble)
              })
          )
        )

        val minData = processed_rdd.foldByKey(processed_rdd.first()._2)((seq1, seq2) => seq1.zip(seq2).map(x => scala.math.min(x._1,x._2))).collectAsMap()
        val maxData = processed_rdd.foldByKey(processed_rdd.first()._2)((seq1, seq2) => seq1.zip(seq2).map(x => scala.math.max(x._1,x._2))).collectAsMap()


        var F2 = 0.0
        var new_classes = classes
        classes.foreach(class_name => {

          new_classes = new_classes.drop(1)

          new_classes.foreach(class2_name => {

            var twoClassF2 = 1.0
            processed_rdd.first._2.zipWithIndex.foreach(tuple => {

              val minmaxi = scala.math.min(maxData(class_name)(tuple._2), maxData(class2_name)(tuple._2))
              val maxmini = scala.math.max(minData(class_name)(tuple._2), minData(class2_name)(tuple._2))
              val maxmaxi = scala.math.max(maxData(class_name)(tuple._2), maxData(class2_name)(tuple._2))
              val minmini = scala.math.min(minData(class_name)(tuple._2), minData(class2_name)(tuple._2))
              if (maxmaxi - minmini != 0) {
                twoClassF2 += (minmaxi - maxmini) / (maxmaxi - minmini)
              }

            })

            F2 += math.abs(twoClassF2)

          })


        })

        F2 / processed_rdd.first._2.length

      }


    }


  }