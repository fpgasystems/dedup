package dedup
package hashtable

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

/* Lookup Engine = Arbitration logic + FSM array + lock table 
  make sure seq instr in, seq res out in same order*/

case class HashTableConfig (hashValWidth: Int = 256, ptrWidth: Int = 32, hashTableSize: Int = (1<<20), expBucketSize: Int = 32, hashTableOffset: BigInt = 0) {
  assert(hashTableSize%expBucketSize==0, "Hash table size (#entry) should be a multiple of bucketSize")
  // #hash index
  val idxBucketWidth = log2Up(hashTableSize / expBucketSize)
  val nBucket = 1 << idxBucketWidth
  // property of bucket metadata
  val bucketMetaDataType = Bits(512 bits)
  val bucketMetaDataByteSize = bucketMetaDataType.getBitsWidth/8
  val bucketMetaDataAddrBitShift = log2Up(bucketMetaDataByteSize)

  // property of one entry
  val entryType = Bits(512 bits)
  val entryByteSize = entryType.getBitsWidth/8
  val entryAddrBitShift = log2Up(entryByteSize)
  // memory offset (start of hash table content), minus 1 entry since no index 0.
  val hashTableContentOffset = hashTableOffset + bucketMetaDataByteSize * nBucket - 1 * entryByteSize

  // Lookup FSM settings
  /** Hardware parameters (performance related) */
  val cmdQDepth = 4
  
  // lookup engine settings
  val sizeFSMArray = 1
}

case class HashTableLookupEngineIO(htConf: HashTableConfig) extends Bundle {
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

case class HashTableLookupEngine(htConf: HashTableConfig) extends Component {

  val io = HashTableLookupEngineIO(htConf)

  /** default status of streams */
  io.instrStrmIn.setBlocked()
  io.res.setIdle()
  io.axiMem.setIdle()

  /** Resource */
  val rInitDone = RegInit(False)
  io.initDone := rInitDone

  val memInitializer = new HashTableMemInitializer(htConf)

  val dispatchedInstrStream = StreamDispatcherSequential(io.instrStrmIn, htConf.sizeFSMArray)

  val fsmResBufferArray = Array.fill(htConf.sizeFSMArray)(new StreamFifo(HashTableLookupFSMRes(htConf), 4))
  val fsmArray = Array.tabulate(htConf.sizeFSMArray){idx => 
    val fsmInstance = new HashTableLookupFSM(htConf,idx)
    // connect instr dispatcher to fsm
    dispatchedInstrStream(idx).queue(4) >> fsmInstance.io.instrStrmIn
    // connect fsm results to output
    fsmInstance.io.res >> fsmResBufferArray(idx).io.push
    fsmInstance
  }

  io.res << StreamArbiterFactory.sequentialOrder.transactionLock.on(Array.tabulate(htConf.sizeFSMArray)(idx => fsmResBufferArray(idx).io.pop))
  // val resMerger = new Area{
  //   val fsmSelect = Counter(htConf.sizeFSMArray, io.res.fire)
  //   val dispatchedInstrStream = StreamDemux(io.instrStrmIn, fsmSelect, htConf.sizeFSMArray)
  // }

  // val lockTable



}