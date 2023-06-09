package dedup

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._

import scala.collection.mutable
import scala.collection.mutable._
import scala.util.Random

class DedupCoreNewTests extends AnyFunSuite {
  def dedupCoreNewSim(): Unit = {

    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new WrapDedupCore())
    else SimConfig.withWave.compile(new WrapDedupCore())

    compiledRTL.doSim { dut =>
      DedupCoreNewSim.doSim(dut)
    }
  }

  test("CoreNew Test: New Core with no BF and dummy allocator"){
    dedupCoreNewSim()
  }
}


object DedupCoreNewSim {

  def doSim(dut: WrapDedupCore, verbose: Boolean = false): Unit = {
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(1000000)
    dut.io.pgStrmIn.valid #= false
    dut.io.opStrmIn.valid #= false
    /** memory model for HashTab */
    SimDriver.instAxiMemSim(dut.io.axiMem, dut.clockDomain, None)

    dut.clockDomain.waitSampling(10)

    /** init */
    dut.io.initEn #= true
    dut.clockDomain.waitSampling()
    dut.io.initEn #= false
    dut.clockDomain.waitSamplingWhere(dut.io.initDone.toBoolean)

    dut.io.factorThrou         #= 16
    dut.io.SSDDataIn .ready    #= true
    dut.io.SSDDataOut.valid    #= false
    dut.io.SSDDataOut.fragment #= 0
    dut.io.SSDDataOut.last     #= false
    dut.io.SSDInstrIn.ready    #= true

    /** generate page stream */
    val pageNum =  64
    val dupFacotr = 2
    val opNum = 2
    assert(pageNum%dupFacotr==0, "pageNumber must be a multiple of dupFactor")
    assert(pageNum%opNum==0, "pageNumber must be a multiple of operation number")
    val uniquePageNum = pageNum/dupFacotr
    val pagePerOp = pageNum/opNum
    val pageSize = 4096
    val bytePerWord = 64

    // random data
    // val uniquePgData = List.fill[BigInt](uniquePageNum*pageSize/bytePerWord)(BigInt(bytePerWord*8, Random))
    // fill all word with page index
    val uniquePgData = List.tabulate[BigInt](uniquePageNum*pageSize/bytePerWord){idx => idx/(pageSize/bytePerWord)}
    
    var opStrmData: ListBuffer[BigInt] = ListBuffer()
    for (i <- 0 until opNum) {
      opStrmData.append(SimInstrHelpers.writeInstrGen(2*i*pagePerOp,pagePerOp))
    }

    var pgStrmData: ListBuffer[BigInt] = ListBuffer()

    // 1,1,2,2,...,N,N
    // for (i <- 0 until uniquePageNum) {
    //   for (j <- 0 until dupFacotr) {
    //     for (k <- 0 until pageSize/bytePerWord) {
    //       pgStrmData.append(uniquePgData(i*pageSize/bytePerWord+k))
    //     }
    //   }
    // }

    // 1,...,N, 1,...,N
    for (j <- 0 until dupFacotr) {
      for (i <- 0 until uniquePageNum) {
        for (k <- 0 until pageSize/bytePerWord) {
          pgStrmData.append(uniquePgData(i*pageSize/bytePerWord+k))
        }
      }
    }
    val goldenPgIsNew = List.tabulate[BigInt](pageNum){idx => 1 - (idx/(uniquePageNum))}
    val goldenpgRefCount = List.tabulate[BigInt](pageNum){idx => (idx/(uniquePageNum)) + 1}
    val goldenPgIdx = List.tabulate[BigInt](pageNum){idx => ((idx/pagePerOp)) * pagePerOp + idx} // start + idx

    // random shuffle
    // var initialPgOrder: List[Int] = List()
    // for (j <- 0 until dupFacotr) {
    //   initialPgOrder = initialPgOrder ++ List.range(0, uniquePageNum) // page order: 1,2,...,N,1,2,...,N,...
    // }

    // val shuffledPgOrder = Random.shuffle(initialPgOrder)
    // assert(shuffledPgOrder.length == pageNum, "Total page number must be the same as the predefined parameter")
    // for (pgIdx <- 0 until pageNum) {
    //   val uniquePgIdx = shuffledPgOrder(pgIdx)
    //   for (k <- 0 until pageSize/bytePerWord) {
    //     pgStrmData.append(uniquePgData(uniquePgIdx * pageSize/bytePerWord+k))
    //   }
    // }

    /* Stimuli injection */
    val opStrmPush = fork {
      var opIdx: Int = 0
      for (opIdx <- 0 until opNum) {
        dut.io.opStrmIn.sendData(dut.clockDomain, opStrmData(opIdx))
      }
    }

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
      for (pageIdx <- 0 until pageNum) {
        val respData     = dut.io.pgResp.recvData(dut.clockDomain)
        val SHA3Hash     = SimHelpers.bigIntTruncVal(respData, 255, 0)
        val RefCount     = SimHelpers.bigIntTruncVal(respData, 287, 256)
        val SSDLBA       = SimHelpers.bigIntTruncVal(respData, 319, 288)
        val hostLBAStart = SimHelpers.bigIntTruncVal(respData, 351, 320)
        val hostLBALen   = SimHelpers.bigIntTruncVal(respData, 383, 352)
        val isExec       = SimHelpers.bigIntTruncVal(respData, 384, 384)
        val opCode       = SimHelpers.bigIntTruncVal(respData, 386, 385)
        
        assert(RefCount == goldenpgRefCount(pageIdx))
        assert(hostLBAStart == goldenPgIdx(pageIdx))
        assert(hostLBALen == 1)
        assert(isExec == goldenPgIsNew(pageIdx))
        assert(opCode == 0)

        // println(s"pageIdx: ${pageIdx}")
        // println(s"${RefCount} == ${goldenpgRefCount(pageIdx)}")
        // println(s"${hostLBAStart} == ${goldenPgIdx(pageIdx)}")
        // println(s"${hostLBALen} == 1")
        // println(s"${isExec} == ${goldenPgIsNew(pageIdx)}")
        // println(s"${opCode} == 0")
      }
    }

    opStrmPush.join()
    pgStrmPush.join()
    pgWrRespWatch.join()

  }
}