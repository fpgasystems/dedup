package dedup
package hashtable

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

case class HashTableConfig (hashValWidth: Int = 256, ptrWidth: Int = 32, hashTableSize: Int = (1<<20), expBucketSize: Int = 32, hashTableOffset: BigInt = 0) {
  // Instr Decoder
  val readyQueueLogDepth = 4
  val waitingQueueLogDepth = 3
  val instrTagWidth = if (readyQueueLogDepth > waitingQueueLogDepth) (readyQueueLogDepth + 1) else (waitingQueueLogDepth + 1)

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
  val sizeFSMArray = 8
}

case class HashTableSSIO(conf: DedupConfig) extends Bundle {
  val initEn       = in Bool () 
  val initDone     = out Bool ()
  val opStrmIn     = slave Stream (Bits(conf.instrTotalWidth bits))
  val pgStrmFrgmIn = slave Stream (Fragment(Bits(conf.wordSizeBit bits)))
  val res          = master Stream (HashTableLookupFSMRes(conf.htConf))
  /** DRAM interface */
  val axiConf     = Axi4ConfigAlveo.u55cHBM
  val axiMem      = master(Axi4(axiConf))
}

class HashTableSubSystem(conf : DedupConfig) extends Component {
  val io     = HashTableSSIO(conf)

  val htConf = conf.htConf

  val sha3Grp   = new SHA3Group(conf.sha3Conf)

  val SHA3ResQueue = StreamFifo(Bits(htConf.hashValWidth bits), 4)
  
  val instrDecoder = HashTableInstrDecoder(conf)

  val decodedWaitingInstrQueue = StreamFifo(DecodedWaitingInstr(conf),  1 << htConf.waitingQueueLogDepth)

  val decodedReadyInstrQueue = StreamFifo(DecodedReadyInstr(conf),  1 << htConf.readyQueueLogDepth)

  val instrIssuer = HashTableInstrIssuer(conf)

  val lookupEngine = HashTableLookupEngine(htConf)

  val memAllocator = new Area{
    // dummy allocator
    val io = new Bundle{
      val mallocIdx   = Stream(UInt(htConf.ptrWidth bits))
      val freeIdx     = Stream(UInt(htConf.ptrWidth bits))
    }

    io.freeIdx.freeRun()
    val freeIdxGen = Counter(htConf.ptrWidth bits, io.mallocIdx.fire)
    io.mallocIdx.payload := freeIdxGen.value + U(1)
    io.mallocIdx.valid   := True
  }

  io.initDone := lookupEngine.io.initDone

  // SHA3 Group + SHA3 res Queue
  sha3Grp.io.initEn := io.initEn
  sha3Grp.io.frgmIn << io.pgStrmFrgmIn

  SHA3ResQueue.io.push  << sha3Grp.io.res
  SHA3ResQueue.io.flush := io.initEn

  // decoder + decoded instr Queue
  io.opStrmIn                        >> instrDecoder.io.rawInstrStream
  instrDecoder.io.readyInstrStream   >> decodedReadyInstrQueue.io.push
  instrDecoder.io.waitingInstrStream >> decodedWaitingInstrQueue.io.push

  decodedReadyInstrQueue.io.flush    := io.initEn
  decodedWaitingInstrQueue.io.flush  := io.initEn

  // instr issuer and lookup Engine
  instrIssuer.io.initEn              := io.initEn
  instrIssuer.io.readyInstrStream    << decodedReadyInstrQueue.io.pop
  instrIssuer.io.waitingInstrStream  << decodedWaitingInstrQueue.io.pop
  instrIssuer.io.SHA3ResStream       << SHA3ResQueue.io.pop

  instrIssuer.io.instrIssueStream    >> lookupEngine.io.instrStrmIn

  lookupEngine.io.initEn             := io.initEn
  lookupEngine.io.res                >> io.res
  lookupEngine.io.axiMem             >> io.axiMem

  lookupEngine.io.mallocIdx          << memAllocator.io.mallocIdx
  lookupEngine.io.freeIdx            >> memAllocator.io.freeIdx
}

