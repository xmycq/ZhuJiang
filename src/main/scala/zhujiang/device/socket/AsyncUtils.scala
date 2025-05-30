package zhujiang.device.socket

import chisel3._
import chisel3.util.DecoupledIO
import freechips.rocketchip.util.{AsyncBundle, AsyncQueueParams, AsyncQueueSink, AsyncQueueSource}
import org.chipsalliance.cde.config.Parameters
import xijiang.Node
import xijiang.router.base.{DeviceIcnBundle, IcnBundle}
import xs.utils.debug.HardwareAssertionKey
import zhujiang.chi.FlitHelper.connIcn
import zhujiang.{ZJBundle, ZJModule}

object AsyncUtils {
  val params = AsyncQueueParams(depth = 4, sync = 2)
}

class AsyncSink[T <: Data](gen: T) extends AsyncQueueSink(gen, AsyncUtils.params)

class AsyncSource[T <: Data](gen: T) extends AsyncQueueSource(gen, AsyncUtils.params)

trait BaseAsyncIcnMonoBundle {
  def req: Option[AsyncBundle[UInt]]
  def hpr: Option[AsyncBundle[UInt]]
  def resp: Option[AsyncBundle[UInt]]
  def data: Option[AsyncBundle[UInt]]
  def snoop: Option[AsyncBundle[UInt]]
  def debug: Option[AsyncBundle[UInt]]
  private lazy val _bundleMap = Seq(
    "REQ" -> req,
    "RSP" -> resp,
    "DAT" -> data,
    "SNP" -> snoop,
    "HPR" -> hpr,
    "DBG" -> debug,
    "ERQ" -> req
  )
  lazy val bundleMap = _bundleMap.flatMap(elm => Option.when(elm._2.isDefined)(elm._1, elm._2.get)).toMap
}

class IcnTxAsyncBundle(node: Node)(implicit p: Parameters) extends ZJBundle with BaseAsyncIcnMonoBundle {
  val req = if(node.ejects.contains("REQ")) {
    Some(new AsyncBundle(UInt(rreqFlitBits.W), AsyncUtils.params))
  } else if(node.ejects.contains("ERQ")) {
    Some(new AsyncBundle(UInt(hreqFlitBits.W), AsyncUtils.params))
  } else None

  val hpr = if(node.ejects.contains("HPR")) {
    Some(new AsyncBundle(UInt(rreqFlitBits.W), AsyncUtils.params))
  } else None

  val resp = if(node.ejects.contains("RSP")) {
    Some(new AsyncBundle(UInt(respFlitBits.W), AsyncUtils.params))
  } else None

  val data = if(node.ejects.contains("DAT")) {
    Some(new AsyncBundle(UInt(dataFlitBits.W), AsyncUtils.params))
  } else None

  val snoop = if(node.ejects.contains("SNP")) {
    Some(new AsyncBundle(UInt(snoopFlitBits.W), AsyncUtils.params))
  } else None

  val debug = if(node.ejects.contains("DBG") && p(HardwareAssertionKey).enable) {
    Some(new AsyncBundle(UInt(debugFlitBits.W), AsyncUtils.params))
  } else None
}

class IcnRxAsyncBundle(node: Node)(implicit p: Parameters) extends ZJBundle with BaseAsyncIcnMonoBundle {
  val req = if(node.injects.contains("REQ")) {
    Some(Flipped(new AsyncBundle(UInt(rreqFlitBits.W), AsyncUtils.params)))
  } else if(node.injects.contains("ERQ")) {
    Some(Flipped(new AsyncBundle(UInt(hreqFlitBits.W), AsyncUtils.params)))
  } else None

  val hpr = if(node.injects.contains("HPR")) {
    Some(Flipped(new AsyncBundle(UInt(rreqFlitBits.W), AsyncUtils.params)))
  } else None

  val resp = if(node.injects.contains("RSP")) {
    Some(Flipped(new AsyncBundle(UInt(respFlitBits.W), AsyncUtils.params)))
  } else None

  val data = if(node.injects.contains("DAT")) {
    Some(Flipped(new AsyncBundle(UInt(dataFlitBits.W), AsyncUtils.params)))
  } else None

  val snoop = if(node.injects.contains("SNP")) {
    Some(Flipped(new AsyncBundle(UInt(snoopFlitBits.W), AsyncUtils.params)))
  } else None

  val debug = if(node.injects.contains("DBG") && p(HardwareAssertionKey).enable) {
    Some(Flipped(new AsyncBundle(UInt(debugFlitBits.W), AsyncUtils.params)))
  } else None
}

class IcnAsyncBundle(val node: Node)(implicit p: Parameters) extends ZJBundle {
  val tx = new IcnTxAsyncBundle(node)
  val rx = new IcnRxAsyncBundle(node)
  def <>(that: DeviceIcnAsyncBundle): Unit = {
    this.rx <> that.tx
    that.rx <> this.tx
  }
}

class DeviceIcnAsyncBundle(val node: Node)(implicit p: Parameters) extends ZJBundle {
  val tx = Flipped(new IcnRxAsyncBundle(node))
  val rx = Flipped(new IcnTxAsyncBundle(node))
  def <>(that: IcnAsyncBundle): Unit = {
    this.rx <> that.tx
    that.rx <> this.tx
  }
}

abstract class BaseIcnAsyncModule(node: Node, icnSide: Boolean)(implicit p: Parameters) extends ZJModule {
  def toAsync(async: AsyncBundle[UInt], sync: DecoupledIO[Data]) = {
    val asyncSource = Module(new AsyncSource(sync.bits.asUInt.cloneType))
    connIcn(asyncSource.io.enq, sync)
    async <> asyncSource.io.async
    asyncSource
  }
  def fromAsync(sync: DecoupledIO[Data], async: AsyncBundle[UInt]) = {
    val asyncSink = Module(new AsyncSink(sync.bits.asUInt.cloneType))
    asyncSink.io.async <> async
    connIcn(sync, asyncSink.io.deq)
    asyncSink
  }
}

class IcnSideAsyncModule(node: Node)(implicit p: Parameters) extends BaseIcnAsyncModule(node = node, icnSide = true) {
  val io = IO(new Bundle {
    val dev = new DeviceIcnBundle(node)
    val async = new IcnAsyncBundle(node)
  })

  for(chn <- node.ejects) {
    val rx = io.dev.rx.bundleMap(chn)
    val tx = io.async.tx.bundleMap(chn)
    val ax = toAsync(tx, rx)
    ax.suggestName(s"async_src_${chn.toLowerCase}")

  }

  for(chn <- node.injects) {
    val rx = io.async.rx.bundleMap(chn)
    val tx = io.dev.tx.bundleMap(chn)
    val ax = fromAsync(tx, rx)
    ax.suggestName(s"async_sink_${chn.toLowerCase}")
  }
}

class DeviceSideAsyncModule(node: Node)(implicit p: Parameters) extends BaseIcnAsyncModule(node = node, icnSide = false) {
  val io = IO(new Bundle {
    val icn = new IcnBundle(node)
    val async = new DeviceIcnAsyncBundle(node)
  })
  for(chn <- node.ejects) {
    val rx = io.async.rx.bundleMap(chn)
    val tx = io.icn.tx.bundleMap(chn)
    val ax = fromAsync(tx, rx)
    ax.suggestName(s"async_sink_${chn.toLowerCase}")
  }

  for(chn <- node.injects) {
    val rx = io.icn.rx.bundleMap(chn)
    val tx = io.async.tx.bundleMap(chn)
    val ax = toAsync(tx, rx)
    ax.suggestName(s"async_src_${chn.toLowerCase}")
  }
}