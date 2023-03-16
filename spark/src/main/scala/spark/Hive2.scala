package spark

import org.apache.spark.SparkConf
import org.apache.spark.sql.functions.{col, lit, when}
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.sql.{Row, SaveMode, SparkSession}


import java.util.Properties
import scala.collection.mutable.ListBuffer

/*
  1.spark版本变更为2.3.3，部署模式local即可。也可探索其他模式。
  2.由于远程调试出现的各种问题，且远程调试并非作业重点，这里重新建议使用spark-submit方式
  3.本代码及spark命令均为最简单配置。如运行出现资源问题，请根据你的机器情况调整conf的配置以及spark-submit的参数，具体指分配CPU核数和分配内存。

  调试：
    当前代码中集成了spark-sql，可在开发机如windows运行调试;
    需要在开发机本地下载hadoop，因为hadoop基于Linux编写，在开发机本地调试需要其中的一些文件，如模拟Linux目录系统的winutils.exe；
    请修改System.setProperty("hadoop.home.dir", "your hadoop path in windows like E:\\hadoop-x.x.x")

  部署：
    注释掉System.setProperty("hadoop.home.dir", "your hadoop path in windows like E:\\hadoop-x.x.x")；
    修改pom.xml中<scope.mode>compile</scope.mode>为<scope.mode>provided</scope.mode>
    打包 mvn clean package
    上传到你的Linux机器

    注意在~/base_profile文件中配置$SPARK_HOME,并source ~/base_profile,或在bin目录下启动spark-submit
    spark-submit Spark2DB-1.0.jar
 */


object Hive2 {
  // parameters
  LoggerUtil.setSparkLogLevels()

  def main(args: Array[String]): Unit = {
    System.setProperty("hadoop.home.dir", "C:\\hadoop")
    //以local模式部署spark
    val conf = new SparkConf()
      .setAppName(this.getClass.getSimpleName)
      .setMaster("local[2]")

    //sparksession 读取数据入口
    val session = SparkSession.builder()
      .config(conf)
      .getOrCreate()

    //利用官方hive驱动连接远程hive静态仓库，DataFrameReader
    val reader = session.read.format("jdbc")
      .option("url", "jdbc:hive2://clickhouse:10000/default")
      .option("user", "student")
      .option("password", "nju2023")
      .option("driver", "org.apache.hive.jdbc.HiveDriver")
    //val df = reader.option("dbtable", "tableName").load()
    val registerHiveDqlDialect = new RegisterHiveSqlDialect()
    registerHiveDqlDialect.register()

    var tblNameDst = "pri_cust_contact_info"
    var df = reader.option("dbtable", tblNameDst).load()
    // 去掉列名中的表名前缀，⽅便解析
    val columnNames = df.columns.toList.map(name => name.substring(tblNameDst.length + 1)).toArray
    df = df.toDF(columnNames: _*) // 使⽤简化后的列名构建DataFrame
    // 过滤contact ⽆效数据⾏
    df = df.where("contact != '⽆' and contact != '-' and contact != ''")
    df = df.drop("sys_source", "create_date", "update_date") // 去掉不需要显示的列
    df = df.na.drop(List("contact")) // 丢弃含有null或者NAN的⾏
    df = df.dropDuplicates("uid", "contact") // 对于联系⽅式去重uid
    df = df.withColumn("contact_phone", col("con_type") + 1) // 添加新列
    df = df.withColumn("contact_address", col("con_type") + 1)
    // df.rdd：将dataFrame转换成RDD进⾏操作 rdd.map(row => 对每⾏数据进⾏操作 得到rdd[k,v]格式：（uid，ListBuffer(con_type,contact,,))
    // ListBuffer: 0:con_type 1:contact 2:contact_phone 3:contact_address ，使⽤逗号分割
    var res = df.rdd.map(row => row.getAs("uid").toString -> ListBuffer[String](row.getAs("con_type").toString, row.getAs("contact").toString, "", ""))
    // 对con_type判断来填充ListBuffer
    res = res.map(item => {
      val listBufferValue = item._2
      if (listBufferValue.head == "TEL" || listBufferValue.head == "OTH" || listBufferValue.head == "MOB") {
        listBufferValue(2) = listBufferValue(1)
      } else {
        listBufferValue(3) = listBufferValue(1)
      }
      item
    })
    // RDD 两两聚合 r1 r2 为key相同的两个value，将r2的信息合并到r1上
    res = res.reduceByKey((r1, r2) => {
      if (r2.head == "TEL" || r2.head == "OTH" || r2.head == "MOB") {
        if (r1(2).nonEmpty) {
          r1(2) = r1(2) + "," + r2(1)
        } else {
          r1(2) = r2(1)
        }
      } else { //r2类型为address
        if (r1(3).nonEmpty) {
          r1(3) = r1(3) + "," + r2(1)
        } else {
          r1(3) = r2(1)
        }
      }
      r1
    })
    val structFields = Array(StructField("uid", StringType, true), StructField("contact_phone", StringType, false), StructField("contact_address", StringType, false))
    val structType = StructType(structFields)
    val rdd = res.map(item => Row(item._1, item._2(2), item._2(3)))
    df = session.createDataFrame(rdd, structType)
    val write_maps = Map[String, String](
      "batchsize" -> "2000",
      "isolationLevel" -> "NONE",
      "numPartitions" -> "1")
    //url
    val url = "jdbc:clickhouse://clickhouse:8123/dm"
    val dbtable = tblNameDst
    val pro = new Properties()
    pro.put("driver", "ru.yandex.clickhouse.ClickHouseDriver")
    df.write.mode(SaveMode.Append)
      .options(write_maps)
      .option("user", "default")
      .option("password", "16d808ef")
      .jdbc(url, dbtable, pro)

    //对九张表进⾏去重和过滤（过滤NULL列）以及数据聚合等ETL操作
    val tblNameDsts = List("dm_v_as_djk_info", "dm_v_as_djkfq_info", "pri_credit_info", "pri_cust_asset_acct_info", "pri_cust_asset_info", "pri_cust_base_info", "pri_cust_liab_info", "pri_star_info", "pri_cust_liab_acct_info")
    for (tblNameDst <- tblNameDsts) {
      var df = reader.option("dbtable", tblNameDst).load()
      // 去掉列名中的表名前缀，方便解析
      val columnNames = df.columns.toList.map(name => name.substring(tblNameDst.length + 1)
      ).toArray
      df = df.toDF(columnNames: _*)
      //删掉空值列
      if (tblNameDst.equals("dm_v_as_djk_info")) {
        df = df.drop("bal")
      }
      else if (tblNameDst.equals("dm_v_as_djkfq_info")) {
        df = df.drop("mge_org", "recom_no")
      }
      else if (tblNameDst.equals("pri_cust_asset_acct_info")) {
        df = df.drop("term", "rate", "auto_dp_flg", "matu_date")
      }
      else if (tblNameDst.equals("pri_cust_liab_acct_info")) {
        df = df.drop("grntr_cert_no")
      }

      // 过滤uid为空的数据
      println(tblNameDst)
      df = df.na.drop(Array("uid"))
      // 去除重复的⾏
      df = df.dropDuplicates()

      val dbtable = tblNameDst
      val pro = new Properties()
      pro.put("driver", "ru.yandex.clickhouse.ClickHouseDriver")
      df.write.mode(SaveMode.Append)
        .options(write_maps)
        .option("user", "default")
        .option("password", "16d808ef")
        .jdbc(url, dbtable, pro)
    }
    session.close()
  }
}
