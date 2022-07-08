package org.spark.geospatial

import com.github.davidmoten.geo.GeoHash
import org.apache.sedona.core.serde.SedonaKryoRegistrator
import org.apache.sedona.sql.utils.SedonaSQLRegistrator
import org.apache.spark.SparkConf
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

import java.io.File

object GeoSpatialPlayground {

  def main(args: Array[String]): Unit = {
    val datasetPath = "90per.json"
    val outputPath = "results/"
    runApp(new SparkConf(), datasetPath, outputPath)
  }

  def runApp(sparkConf: SparkConf, datasetPath: String, outputPath: String): Unit = {
    implicit val spark: SparkSession = SparkSession.builder()
      .config(sparkConf)
      .appName("play-geospatial") // Change this to a proper name
      // Enable Sedona custom Kryo serializer
      .config("spark.serializer", classOf[KryoSerializer].getName) // org.apache.spark.serializer.KryoSerializer
      .config("spark.kryo.registrator", classOf[SedonaKryoRegistrator].getName)
      .getOrCreate() // org.apache.sedona.core.serde.SedonaKryoRegistrator

    SedonaSQLRegistrator.registerAll(spark)

    import spark.implicits._
    // not really spark way, but good enough for test task
    downloadFile("https://storage.googleapis.com/pl-spark-assignments/poi-canopy/90per", datasetPath)
    import Columns._
    val inputData = spark.read.json(datasetPath)
    val dataWithGeoHash = inputData
      .withColumn(
        s"$coordinates",
        when($"$polygon".isNull, array(expr(s"ST_GeomFromWKT($coordinate)")))
          .otherwise(expr(s"ST_DumpPoints(ST_GeomFromWKT($polygon))"))
        //can be optimize to take only surface points, not all
      )
      .select(col(s"$venueId"), col(s"$categories"), explode($"$coordinates").as(s"$coordinate"))
      .withColumn(s"$geohash", expr(s"ST_GeoHash($coordinate, 7)"))

    /*
    TODO very heavy(join and distinct operations) and direct approach.
        In theory one more column with less precision geohash which will include geohash and it's neighbour,
        can be used to inflate coordinate from primary geohash with it's neighbour geohashes.
     */
    val isNeighbour = udf((geohash1: String, geohash2: String) => GeoHash.neighbours(geohash1).contains(geohash2))
    val onlyGeohashes = dataWithGeoHash.select($"$geohash".as(s"$neighbourGeohash")).distinct()
    val dataWithInflatedToNeighbourGeohashes =
      dataWithGeoHash
        .join(
          onlyGeohashes,
          isNeighbour(dataWithGeoHash(s"$geohash"), onlyGeohashes(s"$neighbourGeohash"))
            or dataWithGeoHash(s"$geohash").equalTo(onlyGeohashes(s"$neighbourGeohash"))
        )
        .drop(s"$geohash")
        .withColumnRenamed(s"$neighbourGeohash", s"$geohash")

    val result = dataWithInflatedToNeighbourGeohashes
      .groupBy("geohash")
      .agg(count("*").as("count"), collect_list(struct($"$venueId".as("id"), $"$categories")).as("venues"))

    val numOfOutputFiles = 1
    result
      .orderBy($"count".desc)
      .limit(1000)
      .coalesce(numOfOutputFiles)
      .write
      .json(outputPath)
  }

  private def downloadFile(url: String, outputPath: String): Unit = {
    val src = scala.io.Source.fromURL(url)
    val file = new File(outputPath)
    file.getParentFile.mkdirs()
    val out = new java.io.FileWriter(file)
    try {
      out.write(src.mkString)
      out.flush()
      out.close()
    } finally {
      out.close()
      src.close()
    }
  }

  object Columns {
    val coordinates = "coordinates"
    val coordinate = "coordinate"
    val polygon = "polygon"
    val categories = "categories"
    val geohash = "geohash"
    val venueId = "venue_id"
    val neighbourGeohash = "geohash_neighbour"
  }

}
