package dedup
package hashtable


import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

import scala.collection.mutable
import scala.collection.mutable._
import scala.util.Random

class HashTableLookupFSMTests extends AnyFunSuite {
  def hashTableLookupFSMSim(): Unit = {

    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new HashTableLookupFSMTB())
    else SimConfig.withWave.compile(new HashTableLookupFSMTB())

    compiledRTL.doSim { dut =>
      HashTableFSMTBSim.doSim(dut)
    }
  }

  test("HashTableLookupFSMTest"){
    hashTableLookupFSMSim()
  }
}

case class execRes(SHA3Hash: BigInt, RefCount: BigInt, SSDLBA: BigInt, opCode: BigInt)

object HashTableFSMTBSim {

  def doSim(dut: HashTableLookupFSMTB, verbose: Boolean = false): Unit = {
    // val randomWithSeed = new Random(1006045258)
    val randomWithSeed = new Random()
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(1000000)
    dut.io.initEn            #= false 
    dut.io.instrStrmIn.valid #= false
    dut.io.res.ready         #= false
    dut.io.mallocIdx.valid   #= false
    dut.io.freeIdx.ready     #= false

    /** memory model for HashTab */
    SimDriver.instAxiMemSim(dut.io.axiMem, dut.clockDomain, None)

    dut.clockDomain.waitSampling(10)

    /** init */
    dut.io.initEn #= true
    dut.clockDomain.waitSampling()
    dut.io.initEn #= false
    dut.clockDomain.waitSamplingWhere(dut.io.initDone.toBoolean)

    /** generate page stream */
    val numBucketUsed = 8
    val bucketAvgLen = 2
    val numUniqueSHA3 = numBucketUsed * bucketAvgLen

    val uniqueSHA3refCount = 16

    val uniqueSHA3 = List.fill[BigInt](numUniqueSHA3)(BigInt(256, randomWithSeed))

    val goldenHashTableRefCountLayout = ListBuffer.fill[BigInt](numUniqueSHA3)(0)
    val goldenHashTableSSDLBALayout = ListBuffer.fill[BigInt](numUniqueSHA3)(0)

    val goldenResponse: ListBuffer[execRes] = ListBuffer()
    var instrStrmData: ListBuffer[BigInt] = ListBuffer()

    // op gen
    // for (i <- 0 until opNum) {
    //   opStrmData.append(SimInstrHelpers.writeInstrGen(i*pagePerOp,pagePerOp))
    // }
    var pseudoAllocator: Int = 1

    // var pgStrmData: ListBuffer[BigInt] = ListBuffer()

    // 1,...,N, 1,...,N
    for (i <- 0 until uniqueSHA3refCount) {
      for (j <- 0 until numUniqueSHA3) {
        instrStrmData.append(HashTableLookupHelpers.insertInstrGen(uniqueSHA3(j)))
        val isNew = (goldenHashTableRefCountLayout(j) == 0)
        goldenHashTableRefCountLayout.update(j, goldenHashTableRefCountLayout(j) + 1)
        if (isNew) {
          goldenHashTableSSDLBALayout.update(j, pseudoAllocator)
          pseudoAllocator = pseudoAllocator + 1
        }
        goldenResponse.append(execRes(uniqueSHA3(j),goldenHashTableRefCountLayout(j),goldenHashTableSSDLBALayout(j),0))
      }
    }
    
    // 1,...,N, 1,...,N
    // for (j <- 0 until dupFacotr) {
    //   for (i <- 0 until uniquePageNum) {
    //     for (k <- 0 until pageSize/bytePerWord) {
    //       pgStrmData.append(uniquePgData(i*pageSize/bytePerWord+k))
    //     }
    //   }
    // }

    // random shuffle
    // var initialPgOrder: List[Int] = List()
    // for (j <- 0 until dupFacotr) {
    //   initialPgOrder = initialPgOrder ++ List.range(0, uniquePageNum) // page order: 1,2,...,N,1,2,...,N,...
    // }

    // val shuffledPgOrder = randomWithSeed.shuffle(initialPgOrder)
    // assert(shuffledPgOrder.length == pageNum, "Total page number must be the same as the predefined parameter")
    // for (pgIdx <- 0 until pageNum) {
    //   val uniquePgIdx = shuffledPgOrder(pgIdx)
    //   for (k <- 0 until pageSize/bytePerWord) {
    //     pgStrmData.append(uniquePgData(uniquePgIdx * pageSize/bytePerWord+k))
    //   }
    // }

    /* Stimuli injection */
    val instrStrmPush = fork {
      for (instrIdx <- 0 until (uniqueSHA3refCount * numUniqueSHA3)) {
        dut.io.instrStrmIn.sendData(dut.clockDomain, instrStrmData(instrIdx))
      }
    }

    /* pseudo malloc*/
    val pseudoMallocIdxPush = fork {
      for (newBlkIdx <- 0 until numUniqueSHA3) {
        dut.io.mallocIdx.sendData(dut.clockDomain, BigInt(newBlkIdx+1))
      }
    }

    /* pseudo freeUp*/
    // val pseudoFreeIdxWatch = fork {
    //   for (newBlkIdx <- 0 until numUniqueSHA3) {
    //     dut.io.mallocIdx.sendData(dut.clockDomain, BigInt(newBlkIdx+1))
    //   }
    // }

    /* Res watch*/
    val resWatch = fork {
      for (respIdx <- 0 until (uniqueSHA3refCount * numUniqueSHA3)) {
        val respData = dut.io.res.recvData(dut.clockDomain)
        val decodedRealOutput = HashTableLookupHelpers.decodeRes(respData)
        assert(decodedRealOutput == goldenResponse(respIdx))
      }
    }

    instrStrmPush.join()
    pseudoMallocIdxPush.join()
    resWatch.join()

    // // check same result
    // (BFResNew, BFResOld).zipped.map{ case (output, expected) =>
    //   assert(output == expected)
    // }
  }
}

object HashTableLookupHelpers{
  // 128 bucket x 32 entry/bucket = 1<<12 hash table
  val htConf = HashTableConfig (hashValWidth = 256, ptrWidth = 32, hashTableSize = (1<<12), expBucketSize = 32, hashTableOffset = 0)

  def insertInstrGen(SHA3 : BigInt) : BigInt = {
    val sha3_trunc = SimHelpers.bigIntTruncVal(SHA3, 255, 0)

    var lookupInstr = BigInt(0)
    lookupInstr = lookupInstr + (BigInt(0) << (HashTableLookupFSMInstr(htConf).getBitsWidth - DedupCoreOp().getBitsWidth))
    lookupInstr = lookupInstr + (sha3_trunc << 0)
    lookupInstr
  }

  // decodeinstruction from output results
  def decodeRes(respData:BigInt, printRes : Boolean = false) : execRes = {
    val SHA3Hash = SimHelpers.bigIntTruncVal(respData, htConf.hashValWidth - 1, 0)
    val RefCount = SimHelpers.bigIntTruncVal(respData, htConf.hashValWidth + htConf.ptrWidth - 1, htConf.hashValWidth)
    val SSDLBA   = SimHelpers.bigIntTruncVal(respData, htConf.hashValWidth + 2 * htConf.ptrWidth - 1, htConf.hashValWidth + htConf.ptrWidth)
    val opCode   = SimHelpers.bigIntTruncVal(respData, HashTableLookupFSMRes(htConf).getBitsWidth, htConf.hashValWidth + 2 * htConf.ptrWidth)
    execRes(SHA3Hash, RefCount, SSDLBA, opCode)
  }
}

case class HashTableLookupFSMTB() extends Component{

  val conf = DedupConfig()

  val htConf = HashTableLookupHelpers.htConf

  val io = new Bundle {
    val initEn      = in Bool()
    val initDone    = out Bool()
    // execution results
    val instrStrmIn = slave Stream(HashTableLookupFSMInstr(htConf))
    val res         = master Stream(HashTableLookupFSMRes(htConf))
    // interface to allocator
    val mallocIdx   = slave Stream(UInt(htConf.ptrWidth bits))
    val freeIdx     = master Stream(UInt(htConf.ptrWidth bits))

    /** DRAM interface */
    val axiConf     = Axi4ConfigAlveo.u55cHBM
    val axiMem      = master(Axi4(axiConf))
  }

  val memInitializer = HashTableMemInitializer(htConf)

  val lookupFSM = HashTableLookupFSM(htConf)
  
  memInitializer.io.initEn := io.initEn
  lookupFSM.io.initEn      := io.initEn

  val isMemInitDone = memInitializer.io.initDone
  io.initDone := isMemInitDone

  // arbitrate AXI connection
  when(!isMemInitDone){
    memInitializer.io.axiMem >> io.axiMem
    lookupFSM.io.axiMem.setBlocked()
  }.otherwise{
    memInitializer.io.axiMem.setBlocked()
    lookupFSM.io.axiMem >> io.axiMem
  }

  io.mallocIdx >> lookupFSM.io.mallocIdx
  lookupFSM.io.freeIdx >> io.freeIdx
  io.instrStrmIn >> lookupFSM.io.instrStrmIn
  lookupFSM.io.res >> io.res
  lookupFSM.io.lockReq.ready := True
}