package dongjiang.backend

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import zhujiang.chi._
import dongjiang._
import dongjiang.utils._
import dongjiang.bundle._
import xs.utils.debug._
import dongjiang.directory.{DirEntry, DirMsg, HasPackDirMsg}
import dongjiang.frontend._
import dongjiang.frontend.decode._
import zhujiang.chi.ReqOpcode._
import zhujiang.chi.RspOpcode._
import zhujiang.chi.DatOpcode._
import dongjiang.backend._
import dongjiang.backend.WRISTATE._
import dongjiang.data._
import chisel3.experimental.BundleLiterals._
import xs.utils.queue.FastQueue

// ----------------------------------------------------------------------------------------------------- //
// ---------------------------------------- Ctrl Machine State ----------------------------------------- //
// ----------------------------------------------------------------------------------------------------- //
object WRISTATE {
  val width       = 3
  val FREE        = 0x0.U
  val CANNEST     = 0x1.U
  val SENDREQ     = 0x2.U
  val WAITDBID    = 0x3.U
  val DATATASK    = 0x4.U
  val WAITDATA    = 0x5.U
  val CANTNEST    = 0x6.U
  val RESPCMT     = 0x7.U
}

class WriMes(implicit p: Parameters) extends DJBundle {
  // TODO: WriteEvictOrEvict
  // REQ To LAN:
  // CHI: Free --> SendReq --> WaitDBID --> DataTask --> WaitData --> RespCmt --> Free
  // REQ To BBN:
  // CHI: Free --> CanNest --> SendReq --> WaitDBID --> DataTask --> WaitData --> CantNest --> RespCmt --> Free
  val state       = UInt(WRISTATE.width.W)
  val alrGetComp  = Bool() // already get comp from CHI

  def isFree      = state === FREE
  def isValid     = !isFree
  def isCanNest   = state === CANNEST
  def isSendReq   = state === SENDREQ
  def isWaitDBID  = state === WAITDBID
  def isDataTask  = state === DATATASK
  def isWaitData  = state === WAITDATA
  def isCantNest  = state === CANTNEST
  def isUpdNest   = isCanNest | isCantNest
  def isRespCmt   = state === RESPCMT & alrGetComp
}

// ----------------------------------------------------------------------------------------------------- //
// ----------------------------------------- Ctrl Machine Entry ---------------------------------------- //
// ----------------------------------------------------------------------------------------------------- //
class WriteEntry(implicit p: Parameters) extends DJModule {
  /*
   * IO declaration
   */
  val io = IO(new Bundle {
    val config        = new DJConfigIO()
    // Task
    val alloc         = Flipped(Decoupled(new CMTask))
    val resp          = Decoupled(new CMResp)
    // CHI
    val txReq         = Decoupled(new ReqFlit(true))
    val rxRsp         = Flipped(Valid(new RespFlit()))
    // DataTask
    val dataTask      = Decoupled(new DataTask)
    val dataResp      = Flipped(Valid(new HnTxnID()))
    // Update PoS
    val updPosNest    = Decoupled(new PosCanNest)
    // For Debug
    val dbg           = Valid(new ReadState with HasHnTxnID)
  })

  /*
   * Reg and Wire declaration
   */
  val reg   = RegInit((new WriMes with HasPackCMTask).Lit(_.state -> FREE, _.alrGetComp -> false.B))
  val next  = WireInit(reg)

  /*
   * Set QoS
   */
  io.txReq.bits.QoS       := reg.task.qos
  io.resp.bits.qos        := reg.task.qos
  io.dataTask.bits.qos    := reg.task.qos
  io.updPosNest.bits.qos  := reg.task.qos

  /*
   * Output for debug
   */
  io.dbg.valid        := reg.isValid
  io.dbg.bits.state   := reg.state
  io.dbg.bits.hnTxnID := reg.task.hnTxnID


  /*
   * Receive Task
   */
  io.alloc.ready  := reg.isFree

  /*
   * SendReq
   */
  // valid
  io.txReq.valid        := reg.isSendReq
  // bits
  io.txReq.bits         := DontCare
  io.txReq.bits.MemAttr := reg.task.chi.memAttr.asUInt
  io.txReq.bits.Order   := Order.None
  io.txReq.bits.Addr    := Cat(0.U((addrBits - offsetBits).W), reg.task.chi.getOffset)
  io.txReq.bits.Size    := reg.task.chi.getSize
  io.txReq.bits.Opcode  := reg.task.chi.opcode
  io.txReq.bits.TxnID   := reg.task.hnTxnID
  io.txReq.bits.SrcID   := reg.task.chi.getNoC


  /*
   * Update PoS Message
   */
  io.updPosNest.valid       := reg.isUpdNest
  io.updPosNest.bits.hnIdx  := reg.task.getHnIdx
  io.updPosNest.bits.nest   := reg.isCanNest

  /*
   * Send DataTask to DataBlock
   */
  // valid
  io.dataTask.valid               := reg.isDataTask
  // bits
  io.dataTask.bits                := DontCare
  io.dataTask.bits.dataOp         := reg.task.dataOp
  io.dataTask.bits.hnTxnID        := reg.task.hnTxnID
  io.dataTask.bits.ds             := reg.task.ds
  io.dataTask.bits.dataVec        := reg.task.chi.dataVec
  io.dataTask.bits.txDat.Resp     := reg.task.cbResp
  io.dataTask.bits.txDat.Opcode   := Mux(reg.task.chi.isImmediateWrite, NonCopyBackWriteData, CopyBackWriteData)
  io.dataTask.bits.txDat.TxnID    := reg.task.chi.txnID
  io.dataTask.bits.txDat.SrcID    := reg.task.chi.getNoC
  io.dataTask.bits.txDat.TgtID    := reg.task.chi.nodeId

  /*
   * Send Resp To Commit
   */
  // valid
  io.resp.valid               := reg.isRespCmt
  // bits respCmt
  io.resp.bits                := DontCare
  io.resp.bits.hnTxnID        := reg.task.hnTxnID
  io.resp.bits.toRepl         := reg.task.fromRepl
  io.resp.bits.taskInst.valid := true.B

  /*
   * Modify Ctrl Machine Table
   */
  val dbidHit = reg.isValid & io.rxRsp.fire & io.rxRsp.bits.TxnID === reg.task.hnTxnID & (io.rxRsp.bits.Opcode === CompDBIDResp | io.rxRsp.bits.Opcode === DBIDResp)
  val compHit = reg.isValid & io.rxRsp.fire & io.rxRsp.bits.TxnID === reg.task.hnTxnID & (io.rxRsp.bits.Opcode === CompDBIDResp | io.rxRsp.bits.Opcode === Comp)
  // Store Msg From Frontend or CHI
  when(io.alloc.fire) {
    next.task             := io.alloc.bits
    next.alrGetComp       := false.B
  }.elsewhen(dbidHit) {
    next.task.chi.txnID   := io.rxRsp.bits.DBID
    next.task.chi.nodeId  := io.rxRsp.bits.SrcID
    next.alrGetComp       := compHit | reg.alrGetComp
    HAssert.withEn(!reg.alrGetComp, compHit)
  }.elsewhen(compHit) {
    next.alrGetComp       := compHit
    HAssert(!reg.alrGetComp)
  }
  HAssert.withEn(!reg.alrGetComp, compHit)

  // Get Next State
  val dataRespHit = io.dataResp.fire  & io.dataResp.bits.hnTxnID === reg.task.hnTxnID
  switch(reg.state) {
    is(FREE) {
      when(io.alloc.fire)       { next.state := Mux(io.alloc.bits.chi.toBBN, CANNEST, SENDREQ) }
    }
    is(CANNEST) {
      when(io.updPosNest.fire)  { next.state := SENDREQ }
    }
    is(SENDREQ) {
      when(io.txReq.fire)       { next.state := WAITDBID }
    }
    is(WAITDBID) {
      when(dbidHit)             { next.state := DATATASK }
    }
    is(DATATASK) {
      when(io.dataTask.fire)    { next.state := WAITDATA }
    }
    is(WAITDATA) {
      when(dataRespHit)         { next.state := Mux(reg.task.chi.toBBN, CANTNEST, RESPCMT) }
    }
    is(CANTNEST) {
      when(io.updPosNest.fire)  { next.state := RESPCMT }
    }
    is(RESPCMT) {
      when(io.resp.fire)        { next.state := FREE }
    }
  }

  /*
   * HAssert
   */
  HAssert.withEn(reg.isFree,      io.alloc.fire)
  HAssert.withEn(reg.isUpdNest,   reg.isValid & io.updPosNest.fire)
  HAssert.withEn(reg.isWaitDBID,  reg.isValid & dbidHit)
  HAssert.withEn(reg.isDataTask,  reg.isValid & io.dataTask.fire)
  HAssert.withEn(reg.isWaitData,  reg.isValid & dataRespHit)
  HAssert.withEn(reg.isRespCmt,   reg.isValid & io.resp.fire)

  /*
   * Set new task
   */
  val set = io.alloc.fire | reg.isValid; dontTouch(set)
  when(set) { reg := next }

  // HardwareAssertion
  HardwareAssertion.checkTimeout(reg.isFree, TIMEOUT_WRITE, cf"TIMEOUT: Write State[${reg.state}]")
}

// ----------------------------------------------------------------------------------------------------- //
// -------------------------------------------- Ctrl Machine ------------------------------------------- //
// ----------------------------------------------------------------------------------------------------- //
class WriteCM(implicit p: Parameters) extends DJModule {
  /*
   * IO declaration
   */
  val io = IO(new Bundle {
    val config        = new DJConfigIO()
    // Task
    val alloc         = Flipped(Decoupled(new CMTask))
    val resp          = Decoupled(new CMResp)
    // CHI
    val txReq         = Decoupled(new ReqFlit(true))
    val rxRsp         = Flipped(Valid(new RespFlit()))
    // DataTask
    val dataTask      = Decoupled(new DataTask)
    val dataResp      = Flipped(Valid(new HnTxnID()))
    // Update PoS
    val updPosNest    = Decoupled(new PosCanNest)
  })

  /*
   * Module declaration
   */
  val entries = Seq.fill(nrWriteCM) { Module(new WriteEntry()) } // TODO: reserve for toLAN
  val dbgVec  = VecInit(entries.map(_.io.dbg))
  dontTouch(dbgVec)

  /*
   * Connect CM <- IO
   */
  Alloc(entries.map(_.io.alloc), FastQueue(io.alloc))
  entries.foreach(_.io.config   := io.config)
  entries.foreach(_.io.rxRsp    := io.rxRsp)
  entries.foreach(_.io.dataResp := io.dataResp)

  /*
   * Connect IO <- CM
   */
  io.txReq      <> fastQosRRArb(entries.map(_.io.txReq)) // TODO: split to LAN and BBN
  io.resp       <> fastQosRRArb(entries.map(_.io.resp))
  io.dataTask   <> fastQosRRArb(entries.map(_.io.dataTask))
  io.updPosNest <> fastQosRRArb(entries.map(_.io.updPosNest))

  /*
   * HardwareAssertion placePipe
   */
  HardwareAssertion.placePipe(1)
}