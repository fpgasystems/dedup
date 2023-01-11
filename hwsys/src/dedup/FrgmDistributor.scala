package dedup

import spinal.core._
import spinal.lib._

case class FrgmDistributorIO[T <: Data](n: Int, m: Int, frgmType: HardType[T]) extends Bundle {
  val strmI = slave Stream(Fragment(frgmType))
  val strmO = Vec(master Stream(Fragment(frgmType)), n)
}

case class FrgmDistributor[T <: Data](n: Int, m: Int, frgmType: HardType[T]) extends Component {
  val io = FrgmDistributorIO(n, m, frgmType)
  val frgmFire = io.strmI.fire & io.strmI.isLast
  val cntSuperFrgm = Counter(m, frgmFire)
  val cntOutSel = Counter(n, frgmFire & cntSuperFrgm.willOverflow)
  (io.strmO, StreamDemux(io.strmI, cntOutSel, n)).zipped.foreach(_ << _)
}
