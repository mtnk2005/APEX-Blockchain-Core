package com.apex.core

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}

import com.apex.common.{Cache, LRUCache, Serializable}
import com.apex.crypto.Ecdsa.PublicKey
import com.apex.crypto.{UInt160, UInt256}
import com.apex.storage.{Batch, Storage}

class BlockStore(db: Storage.raw, capacity: Int)
  extends StoreBase[UInt256, Block](db, capacity)
    with BlockPrefix
    with UInt256Key
    with BlockValue

class HeaderStore(db: Storage.raw, capacity: Int)
  extends StoreBase[UInt256, BlockHeader](db, capacity)
    with HeaderPrefix
    with UInt256Key
    with BlockHeaderValue

class TransactionStore(db: Storage.raw, capacity: Int)
  extends StoreBase[UInt256, Transaction](db, capacity)
    with TxPrefix
    with UInt256Key
    with TransactionValue

class AccountStore(db: Storage.raw, capacity: Int)
  extends StoreBase[UInt160, Account](db, capacity)
    with AccountPrefix
    with UInt160Key
    with AccountValue

class ContractStore(db: Storage.raw, capacity: Int)
  extends StoreBase[UInt160, Contract](db, capacity)
    with ContractPrefix
    with UInt160Key
    with ContractValue

class ContractStateStore(db: Storage.raw, capacity: Int)
  extends StoreBase[Array[Byte], Array[Byte]](db, capacity)
    with ContractStatePrefix
    with ByteArrayKey
    with ByteArrayValue

class ReceiptStore(db: Storage.raw, capacity:Int)
  extends StoreBase[UInt256, TransactionReceipt](db, capacity)
    with ReceiptPrefix
    with UInt256Key
    with ReceiptValue

class HeightStore(db: Storage.raw, capacity: Int)
  extends StoreBase[Int, UInt256](db, capacity)
    with HeightToIdIndexPrefix
    with IntKey
    with UInt256Value

class BlkTxMappingStore(db: Storage.raw, capacity: Int)
  extends StoreBase[UInt256, BlkTxMapping](db, capacity)
    with BlockIdToTxIdIndexPrefix
    with UInt256Key
    with BlkTxMappingValue

class NameToAccountStore(db: Storage.raw, capacity: Int)
  extends StoreBase[String, UInt160](db, capacity)
    with NameToAccountIndexPrefix
    with StringKey
    with UInt160Value

class ForkItemStore(db: Storage.raw, capacity: Int)
  extends StoreBase[UInt256, ForkItem](db, capacity)
    with ForkItemPrefix
    with UInt256Key
    with ForkItemValue

class HeadBlockStore(db: Storage.raw)
  extends StateStore[BlockHeader](db)
    with HeadBlockStatePrefix
    with BlockHeaderValue

class ProducerStateStore(db: Storage.raw)
  extends StateStore[ProducerStatus](db)
    with ProducerStatePrefix
    with ProducerStatusValue

class LatestConfirmedBlockStore(db: Storage.raw)
  extends StateStore[BlockHeader](db)
    with LatestConfirmedStatePrefix
    with BlockHeaderValue

class SwitchStateStore(db: Storage.raw)
  extends StateStore[SwitchState](db)
    with SwitchStatePrefix
    with SwitchStateValue

object StoreType extends Enumeration {
  val Data = Value(0x00)
  val Index = Value(0x01)
  val State = Value(0x02)
}

object DataType extends Enumeration {
  val BlockHeader = Value(0x00)
  val Transaction = Value(0x01)
  val Account = Value(0x02)
  val Session = Value(0x03)
  val Block = Value(0x04)
  val ForkItem = Value(0x05)
  val Contract = Value(0x06)
  val ContractState = Value(0x07)
  val Receipt = Value(0x08)
}

object IndexType extends Enumeration {
  val BlockHeightToId = Value(0x00)
  val BlockIdToTxId = Value(0x01)
  val UTXO = Value(0x02)
  val NameToAccount = Value(0x03)
}

object StateType extends Enumeration {
  val HeadBlock = Value(0x00)
  val Producer = Value(0x01)
  val LatestConfirmed = Value(0x02)
  val SwitchState = Value(0x03)
}

trait Prefix {
  val prefixBytes: Array[Byte]
}

trait DataPrefix extends Prefix {
  val storeType = StoreType.Data
  val dataType: DataType.Value
  override lazy val prefixBytes: Array[Byte] = Array(storeType.id.toByte, dataType.id.toByte)
}

trait IndexPrefix extends Prefix {
  val storeType = StoreType.Index
  val indexType: IndexType.Value
  override lazy val prefixBytes: Array[Byte] = Array(storeType.id.toByte, indexType.id.toByte)
}

trait StatePrefix extends Prefix {
  val storeType = StoreType.Index
  val stateType: StateType.Value
  override lazy val prefixBytes: Array[Byte] = Array(storeType.id.toByte, stateType.id.toByte)
}

trait BlockPrefix extends DataPrefix {
  override val dataType: DataType.Value = DataType.Block
}

trait HeaderPrefix extends DataPrefix {
  override val dataType: DataType.Value = DataType.BlockHeader
}

trait TxPrefix extends DataPrefix {
  override val dataType: DataType.Value = DataType.Transaction
}

trait AccountPrefix extends DataPrefix {
  override val dataType: DataType.Value = DataType.Account
}

trait ContractPrefix extends DataPrefix {
  override val dataType: DataType.Value = DataType.Contract
}

trait ContractStatePrefix extends DataPrefix {
  override val dataType: DataType.Value = DataType.ContractState
}

trait ReceiptPrefix extends DataPrefix {
  override val dataType: DataType.Value = DataType.Receipt
}

trait ForkItemPrefix extends DataPrefix {
  override val dataType: DataType.Value = DataType.ForkItem
}

trait HeightToIdIndexPrefix extends IndexPrefix {
  override val indexType: IndexType.Value = IndexType.BlockHeightToId
}

trait BlockIdToTxIdIndexPrefix extends IndexPrefix {
  override val indexType: IndexType.Value = IndexType.BlockIdToTxId
}

trait UTXOIndexPrefix extends IndexPrefix {
  override val indexType: IndexType.Value = IndexType.UTXO
}

trait NameToAccountIndexPrefix extends IndexPrefix {
  override val indexType: IndexType.Value = IndexType.NameToAccount
}

trait HeadBlockStatePrefix extends StatePrefix {
  override val stateType: StateType.Value = StateType.HeadBlock
}

trait ProducerStatePrefix extends StatePrefix {
  override val stateType: StateType.Value = StateType.Producer
}

trait LatestConfirmedStatePrefix extends StatePrefix {
  override val stateType: StateType.Value = StateType.LatestConfirmed
}

trait SwitchStatePrefix extends StatePrefix {
  override val stateType: StateType.Value = StateType.SwitchState
}

trait Converter[A] {
  def toBytes(key: A): Array[Byte] = {
    val bs = new ByteArrayOutputStream()
    val os = new DataOutputStream(bs)
    serializer(key, os)
    bs.toByteArray
  }

  def fromBytes(bytes: Array[Byte]): A = {
    val bs = new ByteArrayInputStream(bytes)
    val is = new DataInputStream(bs)
    deserializer(is)
  }

  def deserializer(is: DataInputStream): A

  def serializer(key: A, os: DataOutputStream): Unit
}

trait IntKey extends KeyConverterProvider[Int] {
  override val keyConverter: Converter[Int] = new IntConverter
}

trait StringKey extends KeyConverterProvider[String] {
  override val keyConverter: Converter[String] = new StringConverter
}

trait ByteArrayKey extends KeyConverterProvider[Array[Byte]] {
  override val keyConverter: Converter[Array[Byte]] = new ByteArrayConverter
}

trait UInt160Key extends KeyConverterProvider[UInt160] {
  override val keyConverter: Converter[UInt160] = new SerializableConverter(UInt160.deserialize)
}

trait UInt256Key extends KeyConverterProvider[UInt256] {
  override val keyConverter: Converter[UInt256] = new SerializableConverter(UInt256.deserialize)
}

trait PublicKeyKey extends KeyConverterProvider[PublicKey] {
  override val keyConverter: Converter[PublicKey] = new SerializableConverter(PublicKey.deserialize)
}

trait ContractStateKey extends KeyConverterProvider[Array[Byte]] {
  override val keyConverter: Converter[Array[Byte]] = new ByteArrayConverter
}

//trait UTXOKeyKey extends KeyConverterProvider[UTXOKey] {
//  override val keyConverter: Converter[UTXOKey] = new SerializableConverter(UTXOKey.deserialize)
//}

trait IntValue extends ValueConverterProvider[Int] {
  override val valConverter: Converter[Int] = new IntConverter
}

trait StringValue extends ValueConverterProvider[String] {
  override val valConverter: Converter[String] = new StringConverter
}

trait ByteArrayValue extends ValueConverterProvider[Array[Byte]] {
  override val valConverter: Converter[Array[Byte]] = new ByteArrayConverter
}

trait UInt160Value extends ValueConverterProvider[UInt160] {
  override val valConverter: Converter[UInt160] = new SerializableConverter(UInt160.deserialize)
}

trait UInt256Value extends ValueConverterProvider[UInt256] {
  override val valConverter: Converter[UInt256] = new SerializableConverter(UInt256.deserialize)
}

trait BlockValue extends ValueConverterProvider[Block] {
  override val valConverter: Converter[Block] = new SerializableConverter(Block.deserialize)
}

trait BlockHeaderValue extends ValueConverterProvider[BlockHeader] {
  override val valConverter: Converter[BlockHeader] = new SerializableConverter(BlockHeader.deserialize)
}

trait TransactionValue extends ValueConverterProvider[Transaction] {
  override val valConverter: Converter[Transaction] = new SerializableConverter(Transaction.deserialize)
}

trait AccountValue extends ValueConverterProvider[Account] {
  override val valConverter: Converter[Account] = new SerializableConverter(Account.deserialize)
}

trait ContractValue extends ValueConverterProvider[Contract]{
  override val valConverter: Converter[Contract] = new SerializableConverter(Contract.deserialize)
}

trait ContractStateValue extends ValueConverterProvider[ContractState]{
  override val valConverter: Converter[ContractState] = new SerializableConverter(ContractState.deserialize)
}

trait ReceiptValue extends ValueConverterProvider[TransactionReceipt]{
  override val valConverter: Converter[TransactionReceipt] = new SerializableConverter(TransactionReceipt.deserialize)
}

trait ForkItemValue extends ValueConverterProvider[ForkItem] {
  override val valConverter: Converter[ForkItem] = new SerializableConverter(ForkItem.deserialize)
}

trait BlkTxMappingValue extends ValueConverterProvider[BlkTxMapping] {
  override val valConverter: Converter[BlkTxMapping] = new SerializableConverter(BlkTxMapping.deserialize)
}

trait ProducerStatusValue extends ValueConverterProvider[ProducerStatus] {
  override val valConverter: Converter[ProducerStatus] = new SerializableConverter(ProducerStatus.deserialize)
}

trait HeadBlockValue extends ValueConverterProvider[HeadBlock] {
  override val valConverter: Converter[HeadBlock] = new SerializableConverter(HeadBlock.deserialize)
}

trait SwitchStateValue extends ValueConverterProvider[SwitchState] {
  override val valConverter: Converter[SwitchState] = new SerializableConverter(SwitchState.deserialize)
}

class IntConverter extends Converter[Int] {
  override def deserializer(is: DataInputStream): Int = {
    import com.apex.common.Serializable._
    is.readVarInt
  }

  override def serializer(key: Int, os: DataOutputStream): Unit = {
    import com.apex.common.Serializable._
    os.writeVarInt(key)
  }
}

class StringConverter extends Converter[String] {
  override def deserializer(is: DataInputStream): String = {
    import com.apex.common.Serializable._
    is.readString
  }

  override def serializer(key: String, os: DataOutputStream): Unit = {
    import com.apex.common.Serializable._
    os.writeString(key)
  }
}

class ByteArrayConverter extends Converter[Array[Byte]] {
  override def deserializer(is: DataInputStream): Array[Byte] = {
    import com.apex.common.Serializable._
    is.readByteArray
  }

  override def serializer(key: Array[Byte], os: DataOutputStream): Unit = {
    import com.apex.common.Serializable._
    os.writeByteArray(key)
  }
}

class SerializableConverter[A <: Serializable](f: DataInputStream => A) extends Converter[A] {
  override def deserializer(is: DataInputStream): A = {
    f(is)
  }

  override def serializer(key: A, os: DataOutputStream): Unit = {
    key.serialize(os)
  }
}

trait KeyConverterProvider[A] {
  val keyConverter: Converter[A]
}

trait ValueConverterProvider[A] {
  val valConverter: Converter[A]
}

case class BlkTxMapping(blkId: UInt256, txIds: Seq[UInt256]) extends Serializable {
  override def serialize(os: DataOutputStream): Unit = {
    import com.apex.common.Serializable._
    os.write(blkId)
    os.writeSeq(txIds)
  }
}

object BlkTxMapping {
  def deserialize(is: DataInputStream): BlkTxMapping = {
    import com.apex.common.Serializable._
    BlkTxMapping(
      is.readObj(UInt256.deserialize),
      is.readSeq(UInt256.deserialize)
    )
  }
}

case class HeadBlock(height: Int, id: UInt256) extends Serializable {
  override def serialize(os: DataOutputStream): Unit = {
    os.writeInt(height)
    os.write(id)
  }
}

object HeadBlock {
  final val Size = 4 + UInt256.Size

  def deserialize(is: DataInputStream): HeadBlock = {
    import com.apex.common.Serializable._
    HeadBlock(
      is.readInt(),
      is.readObj(UInt256.deserialize)
    )
  }

  def fromHeader(header: BlockHeader) = {
    HeadBlock(header.index, header.id)
  }
}

case class ProducerStatus(val distance: Long) extends Serializable {
  def plusDistance(n: Long) = this.copy(distance = this.distance + n)

  override def serialize(os: DataOutputStream): Unit = {
    os.writeLong(distance)
  }
}

object ProducerStatus {
  def deserialize(is: DataInputStream): ProducerStatus = {
    ProducerStatus(is.readLong)
  }
}

abstract class StateStore[V <: Serializable](db: Storage.raw) {
  protected var cached: Option[V] = None

  def prefixBytes(): Array[Byte]

  val valConverter: Converter[V]

  def get(): Option[V] = {
    db.get(prefixBytes).map(valConverter.fromBytes)
  }

  def set(value: V, batch: Batch = null): Boolean = {
    db.set(prefixBytes, valConverter.toBytes(value), batch)
  }

  def delete(batch: Batch = null): Unit = {
    db.delete(prefixBytes, batch)
  }
}

abstract class StoreBase[K, V](val db: Storage.raw, cacheCapacity: Int) {
  protected val cache: Cache[K, V] = new LRUCache(cacheCapacity)

  db.onRollback(() => cache.clear)

  val prefixBytes: Array[Byte]

  val keyConverter: Converter[K]

  val valConverter: Converter[V]

  def foreach(func: (K, V) => Unit): Unit = {
    db.find(prefixBytes, (k, v) => {
      val kData = k.drop(prefixBytes.length)
      func(keyConverter.fromBytes(kData),
        valConverter.fromBytes(v))
    })
  }

  def contains(key: K): Boolean = {
    cache.contains(key) || backContains(key)
  }

  def get(key: K): Option[V] = {
    var item = cache.get(key)
    if (item.isEmpty) {
      item = getFromBackStore(key)
      if (item.isDefined) {
        cache.set(key, item.get)
        item
      } else {
        None
      }
    } else {
      item
    }
  }

  def set(key: K, value: V, batch: Batch = null): Boolean = {
    //    val batch = sessionMgr.beginSet(key, value, writeBatch)
    if (setBackStore(key, value, batch)) {
      cache.set(key, value)
      true
    } else {
      false
    }
  }

  def delete(key: K, batch: Batch = null): Boolean = {
    //    val batch = sessionMgr.beginDelete(key, writeBatch)
    if (deleteBackStore(key, batch)) {
      cache.delete(key)
      true
    } else {
      false
    }
  }

  protected def genKey(key: K): Array[Byte] = {
    prefixBytes ++ keyConverter.toBytes(key)
  }

  protected def backContains(key: K): Boolean = {
    db.containsKey(genKey(key))
  }

  protected def getFromBackStore(key: K): Option[V] = {
    db.get(genKey(key)).map(valConverter.fromBytes)
  }

  protected def setBackStore(key: K, value: V, batch: Batch): Boolean = {
    db.set(genKey(key), valConverter.toBytes(value), batch)
  }

  protected def deleteBackStore(key: K, batch: Batch): Boolean = {
    db.delete(genKey(key), batch)
  }
}