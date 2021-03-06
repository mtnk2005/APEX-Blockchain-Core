package com.apex.core

import java.io.{DataInputStream, DataOutputStream}

import com.apex.common.Serializable
import com.apex.crypto.Ecdsa.Point
import com.apex.crypto.FixedNumber

class ValidatorView(val publicKey: Point,
                    val registered: Boolean,
                    val votes: FixedNumber,
                    val version: Int) extends Serializable {
  override def serialize(os: DataOutputStream): Unit = {
    import com.apex.common.Serializable._
    os.writeInt(version)
    os.write(publicKey)
    os.writeBoolean(registered)
    os.write(votes)
  }
}

object ValidatorView {
  def deserialize(is: DataInputStream): ValidatorView = {
    val version = is.readInt
    new ValidatorView(
      publicKey = Point.deserialize(is),
      registered = is.readBoolean,
      votes = FixedNumber.deserialize(is),
      version = version
    )
  }
}