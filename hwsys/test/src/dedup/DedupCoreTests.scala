package dedup

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._

import scala.collection.mutable.ListBuffer
import scala.util.Random

class CoreTests extends AnyFunSuite {
  def dedupCoreSim(): Unit = {

    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new WrapDedupCore())
    else SimConfig.withWave.compile(new WrapDedupCore())

    compiledRTL.doSim { dut =>
      DedupCoreSim.doSim(dut)
    }
  }

  test("DedupCoreTest"){
    dedupCoreSim()
  }
}

object DedupCoreSim {

  def doSim(dut: WrapDedupCore, verbose: Boolean = false): Unit = {
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(1000000)
    /** memory model for HashTab */
    SimDriver.instAxiMemSim(dut.io.axiMem, dut.clockDomain, None)

    dut.clockDomain.waitSampling(10)

    /** init */
    dut.io.initEn #= true
    dut.clockDomain.waitSampling()
    dut.io.initEn #= false
    dut.clockDomain.waitSamplingWhere(dut.io.initDone.toBoolean)

    /** generate page stream */
    val pageNum = 512
    val dupFacotr = 2
    assert(pageNum%dupFacotr==0, "pageNumber must be a multiple of dupFactor")
    val uniquePageNum = pageNum/dupFacotr
    val pageSize = 4096
    val bytePerWord = 64

    val uniquePgData = List.fill[BigInt](uniquePageNum*pageSize/bytePerWord)(BigInt(bytePerWord*8, Random))
    var pgStrmData: ListBuffer[BigInt] = ListBuffer()
    for (i <- 0 until uniquePageNum) {
      for (j <- 0 until dupFacotr) {
        for (k <- 0 until pageSize/bytePerWord) {
//          pgStrmData.append(uniquePgData(i*pageSize/bytePerWord+k) + (BigInt(1)<<32)*j)
          pgStrmData.append(uniquePgData(i*pageSize/bytePerWord+k))
        }
      }
    }


//    for (_ <- 0 until dupFacotr) {
//      pgStrmData = pgStrmData ::: uniquePgData
//    }

    val pgStrmPush = fork {
      var wordIdx: Int = 0
      for (_ <- 0 until pageNum) {
        for (_ <- 0 until pageSize / bytePerWord) {
          dut.io.pgStrmIn.sendData(dut.clockDomain, pgStrmData(wordIdx))
          wordIdx += 1
        }
      }
    }

    val pgWrRespWatch = fork {
      for (_ <- 0 until pageNum) {
        val respData = dut.io.pgResp.recvData(dut.clockDomain)
        val pgIdx = SimHelpers.bigIntTruncVal(respData, 31, 0)
        val pgPtr = SimHelpers.bigIntTruncVal(respData, 95, 32)
        val pgIsExist = SimHelpers.bigIntTruncVal(respData, 96, 96)
        //TODO: now it's simple printing
        println(s"[PageResp] pgIdx=$pgIdx, pgPtr=$pgPtr, pgIsExist=$pgIsExist")
      }
    }

    pgStrmPush.join()
    pgWrRespWatch.join()


  }
}