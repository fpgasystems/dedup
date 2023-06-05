package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import spinal.crypto.hash.sha3._

import util.Stream2StreamFragment
import scala.util.Random

import dedup.bloomfilter.BloomFilterConfig
import dedup.bloomfilter.BloomFilterSubSystem
import dedup.bloomfilter.BloomFilterLookupResEnum

import dedup.hashtable.HashTableConfig

object DedupCoreStatus extends SpinalEnum(binarySequential) {
  val IDLE, OP_READY, WAIT_FOR_DATA, BUSY = newElement()
}

case class DedupConfig() {
  /* general config */
  val pgSize = 4 * 1024 // 4kiB
  val wordSize = 64 // 64B

  val wordSizeBit = wordSize * 8 // 512 bit
  val pgWord = pgSize / wordSize // 64 word per page
  assert(pgSize % wordSize == 0)

  /*instr config*/
  val instrTotalWidth = 512
  val LBAWidth = 32
  val hashInfoTotalWidth = 32 * 3 + 256

  /* instr queue config */
  val instrQueueLogDepth = List(2,6,6) // depth = 4,64,64

  /** config of submodules */
  // Bloom Filter
  // val bfConf = BloomFilterConfig(1 << log2Up(6432424), 3) // optimal value with 4096B page size, 10GB, 50% occupacy
  val bfConf = BloomFilterConfig(1 << log2Up(131072), 3) // simulation test only, avoid long init time
  // SHA3 
  val sha3Conf = SHA3Config(dataWidth = 512, sha3Type = SHA3_256, groupSize = 64)

  val htConf = HashTableConfig()

  val pageWriterConfig = PageWriterConfig()
}

case class WrapDedupCoreIO(conf: DedupConfig) extends Bundle {
  /** input */
  val opStrmIn = slave Stream (Bits(conf.instrTotalWidth bits))
  val pgStrmIn = slave Stream (Bits(conf.wordSizeBit bits))
  /** output */
  val pgResp = master Stream (PageWriterResp(conf.pageWriterConfig))
  /** control signals */
  val initEn = in Bool()
  val initDone = out Bool()
  val status = out Bits(DedupCoreStatus().getBitsWidth bits)

  /** hashTab memory interface */
  val axiMem = master(Axi4(Axi4ConfigAlveo.u55cHBM))

  /** pgStore throughput control factor */
  val factorThrou = in UInt(5 bits)
}

class WrapDedupCore() extends Component {

  val dedupConf = DedupConfig()
  val io = WrapDedupCoreIO(dedupConf)
  
  // /* operation and instruction fetching */
  // // val op = RegInit(DedupCoreOp.NOP)
  // // val instrPgCount = Reg(UInt(dedupConf.instrPgCountWidth bits)) init(0)

  // // val execPgCount = Reg(UInt(dedupConf.instrPgCountWidth bits)) init(0)

  // // val dedupCoreIFFSM = new StateMachine {
  // //   val IDLE                                = new State with EntryPoint
  // //   val OP_READY, WAIT_FOR_DATA, BUSY       = new State

  // //   // initialize
  // //   io.opStrmIn.setBlocked()
  // //   io.status := DedupCoreStatus.IDLE.asBits

  // //   // IDLE: op not ready, will not do anything
  // //   IDLE.whenIsActive {
  // //     io.status := DedupCoreStatus.IDLE.asBits
      
  // //     when(io.initEn){
  // //       // detach
  // //       io.opStrmIn.setBlocked()
  // //     }.elsewhen(io.initDone){
  // //       // connect to op stream
  // //       io.opStrmIn.ready := True

  // //       when(io.opStrmIn.fire){
  // //         op.assignFromBits(io.opStrmIn.payload(dedupConf.instrPgCountWidth, DedupCoreOp().getBitsWidth bits))
  // //         instrPgCount := io.opStrmIn.payload(0, dedupConf.instrPgCountWidth bits).asUInt
  // //         goto(OP_READY)
  // //       }
  // //     }
  // //   }

  // //   //  op is ready
  // //   OP_READY.whenIsActive {
  // //     io.status := DedupCoreStatus.OP_READY.asBits
  // //     // detach op, wait for data
  // //     io.opStrmIn.setBlocked()
      
  // //     when(io.initEn){
  // //       goto(IDLE)
  // //     }.elsewhen(op === DedupCoreOp.NOP | instrPgCount === 0){
  // //       goto(IDLE)
  // //     }.otherwise{
  // //       goto(WAIT_FOR_DATA)
  // //     }
  // //   }

  // //   // WAIT FOR DATA, op is ready, data is not ready 
  // //   WAIT_FOR_DATA.whenIsActive {
  // //     io.status := DedupCoreStatus.WAIT_FOR_DATA.asBits
      
  // //     when(io.initEn){
  // //       goto(IDLE)
  // //     }.otherwise{
  // //       when(io.pgStrmIn.fire) (goto(BUSY))
  // //     }
  // //   }

  // //   // BUSY, consuming data
  // //   BUSY.whenIsActive {
  // //     io.status := DedupCoreStatus.BUSY.asBits

  // //     when(io.initEn){
  // //       goto(IDLE)
  // //     }.otherwise{
  // //       when(io.pgStrmIn.ready === True & io.pgStrmIn.valid === False){
  // //         goto(WAIT_FOR_DATA)
  // //       }.elsewhen(False){
  // //         goto(IDLE)
  // //       }
  // //     }
  // //   }
  // // }


  // /** fragmentize pgStream */
  // // val dataTransContinueCond = dedupCoreIFFSM.isActive(dedupCoreIFFSM.WAIT_FOR_DATA) | dedupCoreIFFSM.isActive(dedupCoreIFFSM.BUSY)
  // // val pgStrmFrgm = Stream2StreamFragment(io.pgStrmIn.continueWhen(dataTransContinueCond), dedupConf.pgWord)
  // val pgStrmFrgm = Stream2StreamFragment(io.pgStrmIn, dedupConf.pgWord)
  // /** stream fork */
  // val (pgStrmBloomFilterSS, pgStrmHashTableSS, pgStrmPageWriter) = StreamFork3(pgStrmFrgm)
  // val (opStrmBloomFilterSS, opStrmHashTableSS, opStrmPageWriter) = StreamFork3(io.opStrmIn)

  // /** modules */
  // val bFilterSS = new BloomFilterSubSystem(dedupConf)
  // val sha3Grp   = new SHA3Group(dedupConf.sha3Conf)
  // val hashTab   = new HashTableSubSystem()
  // // val pgWriter = new PageWriter(PageWriterConfig(), dedupConf.instrPgCountWidth)
  // val pgWriter = new PageWriter(PageWriterConfig())

  // /** bloom filter */
  // bFilterSS.io.opStrmIn     << opStrmBloomFilterSS
  // bFilterSS.io.pgStrmFrgmIn << pgStrmBloomFilterSS

  // /** fork the bloom filter result (bool) to SHA and Store module */
  // val (bFilterRes2SHA, bFilterRes2Store) = StreamFork2(bFilterSS.io.res)

  // /** SHA3 group: 64 SHA3 modules to keep the line rate */
  // sha3Grp.io.frgmIn << pgStrmHashTableSS
  // // sha3Grp.io.res

  // /** Hash table for page SHA3 values
  //  *  queue the bFilterRes2SHA here because the result latency in SHA3Grp
  //  */
  // hashTab.io.cmd.translateFrom(StreamJoin(bFilterRes2SHA.queue(128), sha3Grp.io.res.queue(128)))((a, b) => {
  //   a.verb := (b._1.lookupRes === BloomFilterLookupResEnum.IS_EXIST) ? HashTabVerb.LOOKUP | HashTabVerb.INSERT
  //   a.hashVal := b._2
  //   a.isPostInst := False
  // })
  // hashTab.io.ptrStrm1 << pgWriter.io.ptrStrm1
  // hashTab.io.ptrStrm2 << pgWriter.io.ptrStrm2
  // hashTab.io.res >> pgWriter.io.lookupRes
  // hashTab.io.axiMem <> io.axiMem

  // /** pageWriter */
  // pgWriter.io.frgmIn << pgStrmPageWriter
  // pgWriter.io.bfRes << Stream(Bool()).translateFrom(bFilterRes2Store){(a,b) =>
  //   a := (b.lookupRes === BloomFilterLookupResEnum.IS_EXIST)
  // }
  // pgWriter.io.res >> io.pgResp
  // pgWriter.io.factorThrou := io.factorThrou

  // /** init signals */
  // bFilterSS.io.initEn := io.initEn
  // sha3Grp.io.initEn := io.initEn
  // hashTab.io.initEn := io.initEn
  // pgWriter.io.initEn := io.initEn
  // io.initDone := bFilterSS.io.initDone & hashTab.io.initDone

}
