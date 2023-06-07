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

class HashTableLookupEngineTests extends AnyFunSuite {
  test("HashTableLookupEngineTest0: dummy allocator with sequential dispatcher"){
    // dummy allocator with sequential dispatcher in mallocIdx
    // we can predict the allocated address in simple golden model in this setup
    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new HashTableSimpleLookupEngineTB())
    else SimConfig.withWave.compile(new HashTableSimpleLookupEngineTB())

    compiledRTL.doSim { dut =>
      HashTableSimpleLookupEngineTBSim.doSim(dut)
    }
  }
}

object HashTableSimpleLookupEngineTBSim {
  def doSim(dut: HashTableSimpleLookupEngineTB, verbose: Boolean = false): Unit = {
    // val randomWithSeed = new Random(1006045258)
    val randomWithSeed = new Random()
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(2000000)
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
    val htConf = HashTableLookupHelpers.htConf
    val numBucketUsed = 64
    val bucketAvgLen = 8
    val numUniqueSHA3 = numBucketUsed * bucketAvgLen

    val uniqueSHA3refCount = 4

    assert(numBucketUsed <= htConf.nBucket)
    val bucketMask = ((BigInt(1) << (log2Up( htConf.nBucket) - log2Up(numBucketUsed)) - 1) << log2Up(numBucketUsed))
    val uniqueSHA3 = List.fill[BigInt](numUniqueSHA3)(BigInt(256, randomWithSeed) &~ bucketMask)

    val goldenHashTableRefCountLayout = ListBuffer.fill[BigInt](numUniqueSHA3)(0)
    val goldenHashTableSSDLBALayout = ListBuffer.fill[BigInt](numUniqueSHA3)(0)

    val goldenResponse: ListBuffer[execRes] = ListBuffer()
    var instrStrmData: ListBuffer[BigInt] = ListBuffer()

    // op gen
    // for (i <- 0 until opNum) {
    //   opStrmData.append(SimInstrHelpers.writeInstrGen(i*pagePerOp,pagePerOp))
    // }
    var pseudoAllocator: Seq[Int] = Array.tabulate(htConf.sizeFSMArray)(idx => idx + 1)

    // var pgStrmData: ListBuffer[BigInt] = ListBuffer()

    // 1,...,N, 1,...,N
    var instrIdx = 0
    for (i <- 0 until uniqueSHA3refCount) {
      for (j <- 0 until numUniqueSHA3) {
        instrStrmData.append(HashTableLookupHelpers.insertInstrGen(uniqueSHA3(j)))
        val isNew = (goldenHashTableRefCountLayout(j) == 0)
        goldenHashTableRefCountLayout.update(j, goldenHashTableRefCountLayout(j) + 1)
        if (isNew) {
          val fsmId = instrIdx % htConf.sizeFSMArray
          goldenHashTableSSDLBALayout.update(j, pseudoAllocator(fsmId))
          pseudoAllocator.update(fsmId, pseudoAllocator(fsmId) + htConf.sizeFSMArray)
        }
        goldenResponse.append(execRes(uniqueSHA3(j),goldenHashTableRefCountLayout(j),goldenHashTableSSDLBALayout(j),0))
        instrIdx = instrIdx + 1
      }
    }

    val maxAllocRound = Array.tabulate(htConf.sizeFSMArray)(idx => ((pseudoAllocator(idx) - 1)/htConf.sizeFSMArray)).max
    
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
        dut.clockDomain.waitSampling(63)
        dut.io.instrStrmIn.sendData(dut.clockDomain, instrStrmData(instrIdx))
      }
    }

    /* pseudo malloc*/
    val pseudoMallocIdxPush = fork {
      for (newBlkIdx <- 0 until (32 + maxAllocRound) * htConf.sizeFSMArray) {
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
        // println("we get:")
        // println(decodedRealOutput)
        // println("we expect:")
        // println(goldenResponse(respIdx))
        assert(decodedRealOutput == goldenResponse(respIdx))
      }
    }

    instrStrmPush.join()
    // pseudoMallocIdxPush.join() // no need to wait, since we provide more than needed Idx
    resWatch.join()

    // // check same result
    // (BFResNew, BFResOld).zipped.map{ case (output, expected) =>
    //   assert(output == expected)
    // }
  }
}

case class HashTableSimpleLookupEngineTB() extends Component{

  val conf = DedupConfig()

  val htConf = HashTableLookupHelpers.htConf

  val io = new Bundle {
    val initEn      = in Bool()
    val initDone    = out Bool()
    // execution results
    val instrStrmIn = slave Stream(HashTableLookupFSMInstr(htConf))
    val res         = master Stream(HashTableLookupFSMRes(htConf))
    // To Allocator
    val mallocIdx   = slave Stream(UInt(htConf.ptrWidth bits))
    val freeIdx     = master Stream(UInt(htConf.ptrWidth bits))
    /** DRAM interface */
    val axiConf     = Axi4ConfigAlveo.u55cHBM
    val axiMem      = master(Axi4(axiConf))
  }

  /** default status of streams */
  io.axiMem.setIdle()

  val memInitializer = HashTableMemInitializer(htConf)

  val dispatchedInstrStream = StreamDispatcherSequential(io.instrStrmIn, htConf.sizeFSMArray)
  val dispatchedMallocIdxStream = StreamDispatcherSequential(io.mallocIdx, htConf.sizeFSMArray)

  val fsmResBufferArray = Array.fill(htConf.sizeFSMArray)(new StreamFifo(HashTableLookupFSMRes(htConf), 32))
  val fsmFreeIdxBufferArray = Array.fill(htConf.sizeFSMArray)(new StreamFifo(UInt(htConf.ptrWidth bits), 32))

  val lockManager = HashTableLookupLockManager(htConf)

  val fsmArray = Array.tabulate(htConf.sizeFSMArray){idx => 
    val fsmInstance = HashTableLookupFSM(htConf,idx)
    // connect instr dispatcher to fsm
    dispatchedInstrStream(idx).queue(4) >> fsmInstance.io.instrStrmIn
    dispatchedMallocIdxStream(idx).queue(32) >> fsmInstance.io.mallocIdx
    // connect fsm results to output
    fsmInstance.io.initEn := io.initEn
    fsmInstance.io.res >> fsmResBufferArray(idx).io.push
    fsmInstance.io.freeIdx >> fsmFreeIdxBufferArray(idx).io.push
    fsmInstance.io.lockReq >> lockManager.io.fsmArrayLockReq(idx)
    fsmInstance.io.axiMem >> lockManager.io.fsmArrayDRAMReq(idx)
    fsmInstance
  }     

  io.res << StreamArbiterFactory.sequentialOrder.transactionLock.on(Array.tabulate(htConf.sizeFSMArray)(idx => fsmResBufferArray(idx).io.pop))
  io.freeIdx << StreamArbiterFactory.roundRobin.transactionLock.on(Array.tabulate(htConf.sizeFSMArray)(idx => fsmFreeIdxBufferArray(idx).io.pop))

  // initialization logic
  memInitializer.io.initEn := io.initEn
  val isMemInitDone = memInitializer.io.initDone
  io.initDone := isMemInitDone

  // arbitrate AXI connection
  when(!isMemInitDone){
    memInitializer.io.axiMem >> io.axiMem
    lockManager.io.axiMem.setBlocked()
  }.otherwise{
    memInitializer.io.axiMem.setBlocked()
    lockManager.io.axiMem >> io.axiMem
  }
}