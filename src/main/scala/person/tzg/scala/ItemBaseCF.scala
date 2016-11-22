package person.tzg.scala


import scala.collection.mutable.{ArrayBuffer}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.rdd.RDD
import utils.VaildLogUtils
/**
 * Created by arachis on 2016/11/21.
 * spark 实现基于物品的协同过滤
 * 参考：http://blog.csdn.net/sunbow0/article/details/42737541
 */
object ItemBaseCF extends Serializable {

  val conf = new SparkConf().setAppName(this.getClass.getName)
  val sc = new SparkContext(conf)
  val train_path: String = "/tmp/train.csv"
  val test_path = "/tmp/test.csv"
  val split: String = ","

  /**
   * 加载数据为用户评分矩阵
   * @param sc
   */
  def loadDate(sc: SparkContext): RDD[(String, String, Double)] = {
    //parse to user,item,rating 3tuple
    val ratings = sc.textFile(train_path, 200).filter(l => {
      val fileds = l.split(split)
      fileds.length >=3 && VaildLogUtils.isNumeric(fileds(0)) && VaildLogUtils.isNumeric(fileds(1)) && VaildLogUtils.isNumeric(fileds(2))
    })
      .map(line => {
        val fileds = line.split(split)

        (fileds(0), fileds(1), fileds(2).toDouble)
      })
    ratings
  }

  /**
   * 计算物品共现矩阵
   * @param user_item 用户-物品评分矩阵
   * @return ((item1,item2),freq)
   */
  def calculateCoccurenceMatrix(user_item: RDD[(String, String)]): RDD[((String, String), Int)] = {

    val cooccurence_matrix = user_item.reduceByKey((item1,item2) => item1+"-"+item2 ).flatMap[((String, String), Int)](t2 => {
      val items = t2._2.split("-")
      var list = ArrayBuffer[((String, String), Int)]()
      for(i <- 0 to items.length-1){
        for(j <-i to items.length-1){
          if(items(i) != items(j)){
            val tuple = ( (items(j),items(i)), 1)
            list += tuple
          }
          val tuple2 = ( (items(i),items(j)), 1 )
          list += tuple2
        }
      }
      list
    }).reduceByKey((v1, v2) => v1 + v2)
    cooccurence_matrix
  }

  /**
   * 计算物品的相似度算法
   * @param sim_type 相似度计算方法
   * @param rdd_left
   * @param rdd_right
   * @return 物品i和物品j的相似度
   */
  def similarity(sim_type: String = "cosine_s", rdd_left: RDD[(String, (String, String, Int, Int))], rdd_right: RDD[(String, Int)]): RDD[(String, String, Double)] = {
    return rdd_left.join(rdd_right).map(t2 => {
      val item_i = t2._2._1._1
      val item_j = t2._2._1._2
      val nij = t2._2._1._3
      val ni = t2._2._1._4
      val nj = t2._2._2

      (item_i, item_j, (nij / math.sqrt(ni * nj))) //cosine_s
    })
  }

  def main(args: Array[String]) {
    //to for restart fail app
    //sc.setCheckpointDir("/tmp/checkpointdir/")

    //parse to user,item,rating 3tuple
    val ratings: RDD[(String, String, Double)] = loadDate(sc)

    //convert to user-item matrix
    val user_item: RDD[(String, String)] = ratings.map(t3 => (t3._1, t3._2)).sortByKey()
    //user_item.checkpoint() //Done checkpointing RDD 7 to hdfs://BGP-LF-1RE1222:8020/tmp/checkpointdir/cc64b877-42ca-41a4-b0af-64b9130a4baf/rdd-7
    //user_item.count() //rdd.cache >> rdd.checkpoint
    user_item.cache()
    user_item.count()

    //calculate the cooccurence matrix of item
    val cooccurence_matrix: RDD[((String, String), Int)] = calculateCoccurenceMatrix(user_item)
    //sc.setCheckpointDir("/tmp/checkpointdir/cooccurence_matrix")
    //cooccurence_matrix.checkpoint()


    val same_item_matrix: RDD[((String, String), Int)] = cooccurence_matrix.filter(t2 => {
      t2._1._1 == t2._1._1
    })

    val common_item_matrix: RDD[((String, String), Int)] = cooccurence_matrix.filter(t2 => {
      t2._1._1 != t2._1._1
    })

    //calculate the similarity of between item1 and item2
    val rdd_right: RDD[(String, Int)] = same_item_matrix.map(t2 => {

      val item_i: String = t2._1._1
      val ni: Int = t2._2
      (item_i, ni)
    })

    val rdd_left: RDD[(String, (String, String, Int, Int))] = common_item_matrix.map(t2 => {
      (t2._1._1, (t2._1._1, t2._1._2, t2._2)) //item1,item2,count
    }).join(rdd_right).map(t2 => {
      val item1 = t2._2._1._1
      val item2 = t2._2._1._2
      val nij = t2._2._1._3
      val ni = t2._2._2
      (item2, (item1, item2, nij, ni))
    })

    val rdd_cosine_s: RDD[(String, String, Double)] = similarity("cosine_s", rdd_left, rdd_right)
    sc.setCheckpointDir("/tmp/checkpointdir/rdd_cosine_s")
    rdd_cosine_s.checkpoint()
  }



}
