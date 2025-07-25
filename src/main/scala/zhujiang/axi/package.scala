package zhujiang

import chisel3._
import chisel3.util._

package object axi {
  case class AxiParams(
    addrBits: Int = 48,
    idBits: Int = 12,
    userBits: Int = 0,
    dataBits: Int = 256,
    attr: String = "",
    lenBits: Int = 8,
    sizeBits: Int = 3,
    burstBits: Int = 2,
    cacheBits: Int = 4,
    lockBits: Int = 1,
    qosBits: Int = 4,
    regionBits: Int = 4,
    lastBits:Int = 1
  )

  def AxiSlvParamsCalc(in: Seq[AxiParams]): AxiParams = {
    AxiParams(
      addrBits = in.map(_.addrBits).max,
      idBits = in.map(_.idBits).max + log2Ceil(in.length),
      userBits = in.map(_.userBits).max,
      dataBits = in.map(_.dataBits).max,
      lenBits = in.map(_.lenBits).max,
      sizeBits = in.map(_.sizeBits).max,
      burstBits = in.map(_.burstBits).max,
      cacheBits = in.map(_.cacheBits).max,
      lockBits = in.map(_.lockBits).max,
      qosBits = in.map(_.qosBits).max,
      regionBits = in.map(_.regionBits).max,
    )
  }

  class AxiLiteParams(addrBits: Int, dataBits: Int, attr: String = "") extends AxiParams(
    addrBits = addrBits,
    idBits = 0,
    userBits = 0,
    dataBits = dataBits,
    lenBits = 0,
    sizeBits = 0,
    burstBits = 0,
    cacheBits = 0,
    lockBits = 0,
    qosBits = 0,
    regionBits = 0,
    lastBits = 0
  )

  class AXFlit(params: AxiParams) extends Bundle {
    val id = UInt(params.idBits.W)
    val addr = UInt(params.addrBits.W)
    val len = UInt(params.lenBits.W)
    val size = UInt(params.sizeBits.W)
    val burst = UInt(params.burstBits.W)
    val lock = UInt(params.lockBits.W)
    val cache = UInt(params.cacheBits.W)
    val prot = UInt(3.W)
    val qos = UInt(params.qosBits.W)
    val region = UInt(params.regionBits.W)
    val user = UInt(params.userBits.W)
  }

  class AWFlit(params: AxiParams) extends AXFlit(params)

  class ARFlit(params: AxiParams) extends AXFlit(params)

  class WFlit(params: AxiParams) extends Bundle {
    val data = UInt(params.dataBits.W)
    val strb = UInt((params.dataBits / 8).W)
    val last = UInt(params.lastBits.W)
    val user = UInt(params.userBits.W)
    def _last:Bool = if(params.lastBits > 0 ) last(0) else true.B
  }

  class RFlit(params: AxiParams) extends Bundle {
    val id = UInt(params.idBits.W)
    val data = UInt(params.dataBits.W)
    val resp = UInt(2.W)
    val last = UInt(params.lastBits.W)
    val user = UInt(params.userBits.W)
    def _last:Bool = if(params.lastBits > 0 ) last(0) else true.B
  }

  class BFlit(params: AxiParams) extends Bundle {
    val id = UInt(params.idBits.W)
    val resp = UInt(2.W)
    val user = UInt(params.userBits.W)
  }

  object AxiUtils {
    def extConn(extnl: ExtAxiBundle, intnl: AxiBundle): Unit = {
      for ((chn, bd) <- intnl.elements) {
        val dcp = bd.asInstanceOf[DecoupledIO[Bundle]]
        extnl.elements(s"${chn}valid") <> dcp.valid
        extnl.elements(s"${chn}ready") <> dcp.ready
        for ((field, sig) <- dcp.bits.elements) {
          extnl.elements(s"$chn$field") <> sig
        }
      }
    }

    def getExtnl(intnl: AxiBundle): ExtAxiBundle = {
      val extnl = Wire(new ExtAxiBundle(intnl.params))
      extnl <> intnl
      extnl
    }

    def getIntnl(extnl: ExtAxiBundle): AxiBundle = {
      val intnl = Wire(new AxiBundle(extnl.params))
      intnl <> extnl
      intnl
    }
  }

  class AxiBundle(val params: AxiParams) extends Bundle {
    val aw = Decoupled(new AWFlit(params))
    val ar = Decoupled(new ARFlit(params))
    val w = Decoupled(new WFlit(params))
    val b = Flipped(Decoupled(new BFlit(params)))
    val r = Flipped(Decoupled(new RFlit(params)))

    def <>(that: ExtAxiBundle): Unit = AxiUtils.extConn(that, this)
  }

  class ExtAxiBundle(val params: AxiParams) extends Bundle {
    val awvalid = Output(Bool())
    val awready = Input(Bool())
    val awid = Output(UInt(params.idBits.W))
    val awaddr = Output(UInt(params.addrBits.W))
    val awlen = Output(UInt(params.lenBits.W))
    val awsize = Output(UInt(params.sizeBits.W))
    val awburst = Output(UInt(params.burstBits.W))
    val awlock = Output(UInt(params.lockBits.W))
    val awcache = Output(UInt(params.cacheBits.W))
    val awprot = Output(UInt(3.W))
    val awqos = Output(UInt(params.qosBits.W))
    val awregion = Output(UInt(params.regionBits.W))
    val awuser = Output(UInt(params.userBits.W))

    val arvalid = Output(Bool())
    val arready = Input(Bool())
    val arid = Output(UInt(params.idBits.W))
    val araddr = Output(UInt(params.addrBits.W))
    val arlen = Output(UInt(params.lenBits.W))
    val arsize = Output(UInt(params.sizeBits.W))
    val arburst = Output(UInt(params.burstBits.W))
    val arlock = Output(UInt(params.lockBits.W))
    val arcache = Output(UInt(params.cacheBits.W))
    val arprot = Output(UInt(3.W))
    val arqos = Output(UInt(params.qosBits.W))
    val arregion = Output(UInt(params.regionBits.W))
    val aruser = Output(UInt(params.userBits.W))

    val wvalid = Output(Bool())
    val wready = Input(Bool())
    val wdata = Output(UInt(params.dataBits.W))
    val wstrb = Output(UInt((params.dataBits / 8).W))
    val wlast = Output(UInt(params.lastBits.W))
    val wuser = Output(UInt(params.userBits.W))

    val bvalid = Input(Bool())
    val bready = Output(Bool())
    val bid = Input(UInt(params.idBits.W))
    val bresp = Input(UInt(2.W))
    val buser = Input(UInt(params.userBits.W))

    val rvalid = Input(Bool())
    val rready = Output(Bool())
    val rid = Input(UInt(params.idBits.W))
    val rdata = Input(UInt(params.dataBits.W))
    val rresp = Input(UInt(2.W))
    val rlast = Input(UInt(params.lastBits.W))
    val ruser = Input(UInt(params.userBits.W))

    def <>(that: AxiBundle): Unit = AxiUtils.extConn(this, that)
  }
}
