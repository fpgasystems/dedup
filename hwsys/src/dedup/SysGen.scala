package dedup
import spinal.core._
import spinal.crypto.hash.sha3._

// default generator config
object MySpinalConfig
    extends SpinalConfig(
      targetDirectory = "generated_rtl/",
      defaultConfigForClockDomains = ClockDomainConfig(
        resetKind = SYNC,
        resetActiveLevel = LOW
      )
    )

object GenDefault {
  // import config if exists
  def main(args: Array[String]): Unit =
    MySpinalConfig.generateVerilog {
      val top = new SHA3Core_Std(SHA3_256)
      top.setDefinitionName("dedup_default")
      top
    }
}

object GenBloomFilterCRC {
  // import config if exists
  def main(args: Array[String]): Unit =
    MySpinalConfig.generateVerilog {
      val top = new BloomFilterCRC()
      top.setDefinitionName("dedup_bloomfiltercrc")
      top
    }
}