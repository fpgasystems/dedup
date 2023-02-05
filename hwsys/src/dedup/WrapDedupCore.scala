package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

import util.Stream2StreamFragment
import scala.util.Random

case class DedupConfig() {
  val pgSize = 4 * 1024
  val pgWord = pgSize / 64

  /** config of submodules */
  val pageWriterConfig = PageWriterConfig()
}

case class WrapDedupCoreIO(conf: DedupConfig) extends Bundle {
  /** input */
  val pgStrmIn = slave Stream (Bits(512 bits))
  /** output */
  val pgResp = master Stream (PageWriterResp(conf.pageWriterConfig))
  /** control signals */
  val initEn = in Bool()
  val initDone = out Bool()

  /** hashTab memory interface */
  val axiMem = master(Axi4(Axi4ConfigAlveo.u55cHBM))

  /** pgStore throughput control factor */
  val factorThrou = in UInt(5 bits)
}

class WrapDedupCore() extends Component {

  val dedupConf = DedupConfig()
  val io = WrapDedupCoreIO(dedupConf)

  /** fragmentize pgStream */
  val pgStrmFrgm = Stream2StreamFragment(io.pgStrmIn, dedupConf.pgWord)
  /** stream fork */
  val (pgStrmBF, pgStrmSHA3, pgStrmSTORE) = StreamFork3(pgStrmFrgm)

  /** modules */
  val bFilter = new BloomFilterCRC()
  val sha3Grp = new SHA3Group()
  val hashTab = new HashTab()
  val pgWriter = new PageWriter(PageWriterConfig())

  /** bloom filter */
  bFilter.io.frgmIn.translateFrom(pgStrmBF)((a, b) => {
    /** use the lsb 32b of the input 512b for CRC */
    a.fragment := b.fragment(bFilter.bfConf.dataWidth-1 downto 0)
    a.last := b.last
  })
  /** fork the bloom filter result (bool) to SHA and Store module */
  val (bFilterRes2SHA, bFilterRes2Store) = StreamFork2(bFilter.io.res)

  /** SHA3 group: 64 SHA3 modules to keep the line rate */
  sha3Grp.io.frgmIn << pgStrmSHA3
  // sha3Grp.io.res

  /** Hash table for page SHA3 values
   *  queue the bFilterRes2SHA here because the result latency in SHA3Grp
   */
  hashTab.io.cmd.translateFrom(StreamJoin(bFilterRes2SHA.queue(128), sha3Grp.io.res))((a, b) => {
    a.verb := b._1 ? HashTabVerb.LOOKUP | HashTabVerb.INSERT
    a.hashVal := b._2
    a.isPostInst := False
  })
  hashTab.io.ptrStrm1 << pgWriter.io.ptrStrm1
  hashTab.io.ptrStrm2 << pgWriter.io.ptrStrm2
  hashTab.io.res >> pgWriter.io.lookupRes
  hashTab.io.axiMem <> io.axiMem

  /** pageWriter */
  pgWriter.io.frgmIn << pgStrmSTORE
  pgWriter.io.bfRes << bFilterRes2Store
  pgWriter.io.res >> io.pgResp
  pgWriter.io.factorThrou := io.factorThrou

  /** init signals */
  bFilter.io.initEn := io.initEn
  sha3Grp.io.initEn := io.initEn
  hashTab.io.initEn := io.initEn
  pgWriter.io.initEn := io.initEn
  io.initDone := bFilter.io.initDone & hashTab.io.initDone

}
