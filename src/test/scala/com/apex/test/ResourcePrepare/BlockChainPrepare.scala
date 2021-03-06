package com.apex.test.ResourcePrepare

import java.time.Instant

import com.apex.consensus.ProducerUtil
import com.apex.core._
import com.apex.crypto.Ecdsa.{PrivateKey, PublicKey}
import com.apex.crypto.{BinaryData, Crypto, Ecdsa, FixedNumber, MerkleTree, UInt160}
import com.apex.settings._

import scala.collection.mutable.ArrayBuffer

class BlockChainPrepare {

  var  blockTimeForSchedule: Long = 0
  var  chain: Blockchain = _
  val _produceInterval = 500

  val _minerAward: Double = 12.3

  val priv1 = new PrivateKey(BinaryData("efc382ccc0358f468c2a80f3738211be98e5ae419fc0907cb2f51d3334001471"))
  val priv2 = new PrivateKey(BinaryData("cc7b7fa6e706944fa2d75652065f95ef2f364316e172601320655aac0e648165"))
  val priv3 = new PrivateKey(BinaryData("db71fe7c0ac4ca3e8cef95bf55cf535eaa8fe0c80d18e0cb19af8d7071b8a184"))
  val priv4 = new PrivateKey(BinaryData("9456beec947b368eda4be03f6c306703d9b2eda49f661285944b4e1f07ae18f3"))

  val _witness1 = InitWitness("init1", priv1.publicKey.address)
  val _witness2 = InitWitness("init2", priv2.publicKey.address)
  val _witness3 = InitWitness("init3", priv3.publicKey.address)
  val _witness4 = InitWitness("init4", priv4.publicKey.address)

  val _miners = MinerSettings(Array(
    priv1,
    priv2,
    priv3,
    priv4     ))

  val _consensusSettings = ConsensusSettings(_produceInterval, 500, 1, 4, 5, 2.1, 63000, Array(_witness1, _witness2, _witness3, _witness4))

  val _runtimeParas = RuntimeParas(100)

  val _acct1 = Ecdsa.PrivateKey.fromWIF("KwmuSp41VWBtGSWaQQ82ZRRSFzkJVTAyuDLQ9NzP9CPqLWirh4UQ").get
  val _acct2 = Ecdsa.PrivateKey.fromWIF("L32JpLopG2hWjEMSCkAjS1nUnPixVrDTPqFAGYbddQrtUjRfkjEP").get
  val _acct3 = Ecdsa.PrivateKey.fromWIF("KyUTLv2BeP9SJD6Sa8aHBVmuRkgw9eThjNGJDE4PySEgf2TvCQCn").get
  val _acct4 = Ecdsa.PrivateKey.fromWIF("L33Uh9L35pSoEqBPP43U6rQcD2xMpJ7F4b3QMjUMAL6HZhxUqEGq").get

  private val minerCoinFrom = UInt160.Zero

  def makeTx(from: PrivateKey,
                     to: PrivateKey,
                     amount: FixedNumber,
                     nonce: Long,
                     gasLimit: Long = 21000,
                     gasPrice: FixedNumber = FixedNumber.MinValue,
                     txType: TransactionType.Value = TransactionType.Transfer,
                      executedTime: Long = 0) = {

    val tx = new Transaction(txType, from.publicKey.pubKeyHash, to.publicKey.pubKeyHash,
      amount, nonce, BinaryData.empty, gasPrice, gasLimit, BinaryData.empty, executeTime = executedTime)
    tx.sign(from)
    tx
  }

  def makeBlock(chain: Blockchain,
                        preBlock: Block,
                        txs: Seq[Transaction],
                        award: Double = _minerAward): Block = {
    val blockTime = preBlock.header.timeStamp + _consensusSettings.produceInterval
    val miner = chain.getWitness(blockTime)

    val minerTx = new Transaction(TransactionType.Miner, minerCoinFrom,
      miner, FixedNumber.fromDecimal(award),
      preBlock.height + 1,
      BinaryData(Crypto.randomBytes(8)), // add random bytes to distinct different blocks with same block index during debug in some cases
      FixedNumber.Zero, 0, BinaryData.empty)

    val allTxs = ArrayBuffer.empty[Transaction]

    allTxs.append(minerTx)
    txs.foreach(allTxs.append(_))

    val header: BlockHeader = BlockHeader.build(preBlock.header.index + 1,
      blockTime, MerkleTree.root(allTxs.map(_.id)),
      preBlock.id(), _miners.findPrivKey(miner).get)

    Block.build(header, allTxs)
  }

//  def makeBlock(preBlock: Block,
//                        txs: Seq[Transaction],
//                        award: Double = _minerAward): Block = {
//    val blockTime = preBlock.header.timeStamp + _consensusSettings.produceInterval
//    val miner = chain.getWitness(blockTime)
//
//    val minerTx = new Transaction(TransactionType.Miner, minerCoinFrom,
//      miner, "", FixedNumber.fromDecimal(award),
//      preBlock.height + 1,
//      BinaryData(Crypto.randomBytes(8)), // add random bytes to distinct different blocks with same block index during debug in some cases
//      FixedNumber.Zero, 0, BinaryData.empty)
//
//    val allTxs = ArrayBuffer.empty[Transaction]
//
//    allTxs.append(minerTx)
//    txs.foreach(allTxs.append(_))
//
//    val header: BlockHeader = BlockHeader.build(preBlock.header.index + 1,
//      blockTime, MerkleTree.root(allTxs.map(_.id)),
//      preBlock.id(), _miners.findPrivKey(miner).get)
//
//    Block.build(header, allTxs)
//  }

  private def makeBlockByTime(chain: Blockchain, preBlock: Block,
                              //txs: Seq[Transaction],
                              blockTime: Long): Block = {
    //val blockTime = preBlock.header.timeStamp + _consensusSettings.produceInterval
    val miner = chain.getWitness(blockTime)

    val minerTx = new Transaction(TransactionType.Miner, minerCoinFrom,
      miner, FixedNumber.fromDecimal(_minerAward), preBlock.height + 1,
      BinaryData(Crypto.randomBytes(8)), // add random bytes to distinct different blocks with same block index during debug in some cases
      FixedNumber.Zero, 0, BinaryData.empty)

    val allTxs = ArrayBuffer.empty[Transaction]

    allTxs.append(minerTx)
    //txs.foreach(allTxs.append(_))

    val header: BlockHeader = BlockHeader.build(preBlock.header.index + 1,
      blockTime, MerkleTree.root(allTxs.map(_.id)),
      preBlock.id(), _miners.findPrivKey(miner).get)

    Block.build(header, allTxs)
  }

  def startProduceBlock(chain: Blockchain, blockTime: Long, stopProcessTxTime: Long) = {

    val witness = chain.getWitness(blockTime)
    chain.startProduceBlock(_miners.findPrivKey(witness).get, blockTime, stopProcessTxTime)
  }

  def createChain(baseDir: String)(f : => Unit){
    val chainSetting = ChainSettings(
      BlockBaseSettings(s"$baseDir/block", false, 0, DBType.LevelDB),
      DataBaseSettings(s"$baseDir/data", false, 0, DBType.LevelDB),
      ForkBaseSettings(s"$baseDir/fork", false, 0, DBType.LevelDB),
      _minerAward,
      GenesisSettings(Instant.EPOCH,
        "7a93d447bffe6d89e690f529a3a0bdff8ff6169172458e04849ef1d4eafd7f86",
        Array(CoinAirdrop(_acct1.publicKey.address, 123.12),
          CoinAirdrop(_acct2.publicKey.address, 234.2))
      )
    )
    chain = new Blockchain(chainSetting, _consensusSettings, _runtimeParas, Notification())
    f
  }

  def produceBlock(){
    var nowTime = Instant.now.toEpochMilli
    var blockTime = ProducerUtil.nextBlockTime(chain.getHeadTime(), nowTime, _produceInterval / 10, _produceInterval) //  chain.getHeadTime() + _consensusSettings.produceInterval
    sleepTo(blockTime)
    nowTime = Instant.now.toEpochMilli
    blockTime += _produceInterval
    startProduceBlock(chain, blockTime, Long.MaxValue)
    assert(chain.isProducingBlock())

    nowTime = Instant.now.toEpochMilli
    assert(nowTime < blockTime - 200)
    blockTimeForSchedule = blockTime
    sleepTo(blockTime)
  }

  def createAccount(){

  }

  def sleepTo(time: Long) = {
    val nowTime = Instant.now.toEpochMilli
    if (time > nowTime)
      Thread.sleep(time - nowTime)
  }
}
