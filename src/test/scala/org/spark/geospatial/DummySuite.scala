package org.spark.geospatial

import org.apache.spark.SparkConf
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.io.{File => JFile}
import scala.reflect.io.File

class DummySuite extends AnyFunSuite with BeforeAndAfterAll {

  private val testRootPath = "POI_canopy/"

  override def beforeAll(): Unit = new File(new JFile(testRootPath)).deleteRecursively()

  test("just run in local mode") {
    GeoSpatialPlayground.runApp(
      new SparkConf().setMaster("local[*]"),
      datasetPath = s"$testRootPath/90per.json",
      outputPath = s"$testRootPath/result/"
    )
  }

}
