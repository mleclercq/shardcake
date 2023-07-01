package com.devsisters.shardcake

import com.devsisters.shardcake.PodAddress
import zio.{ RIO, ZIO }
import zio.stream.ZStream

/**
 * A metadata object that allows sending messages to a remote receiver.
 */
final case class Receiver[-M](pod: PodAddress, id: String) { self =>

  /**
   * Send a single message to the remote receiver.
   *
   * Returns false is the receiver is not available anymore. If so, this receiver should be discarded and no further
   * send should be done (they won't do anything except wasting CPU and network bandwidth).
   */
  def send(message: M): RIO[Sharding, Boolean] =
    ZIO.serviceWithZIO[Sharding](_.sendToReceiver(self, message))

  override def toString: String = s"$pod/$id"
}

object Receiver {
  def apply[M](s: String): Option[Receiver[M]] =
    s.split('/').toList match {
      case pod :: id :: Nil => PodAddress(pod).map(Receiver(_, id))
      case _                => None
    }
}
