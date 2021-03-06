package hybrid

import examples.commons.{SimpleBoxTransaction, SimpleBoxTransactionMemPool}
import examples.curvepos.transaction.PublicKey25519NoncedBox
import examples.hybrid.blocks.{HybridBlock, PosBlock, PowBlock, PowBlockCompanion}
import examples.hybrid.history.{HybridHistory, HybridSyncInfo}
import examples.hybrid.state.HBoxStoredState
import examples.hybrid.wallet.HWallet
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.{BoxStateChanges, PrivateKey25519}
import scorex.crypto.hash.Blake2b256
import scorex.testkit.{BlockchainPerformance, BlockchainSanity}

import scala.util.Random


class HybridSanity extends BlockchainSanity[PublicKey25519Proposition,
  SimpleBoxTransaction,
  HybridBlock,
  HybridSyncInfo,
  PublicKey25519NoncedBox,
  SimpleBoxTransactionMemPool,
  HBoxStoredState,
  HybridHistory] with BlockchainPerformance[PublicKey25519Proposition,
  SimpleBoxTransaction,
  HybridBlock,
  HybridSyncInfo,
  PublicKey25519NoncedBox,
  SimpleBoxTransactionMemPool,
  HBoxStoredState,
  HybridHistory]
  with HybridGenerators {

  //Node view components
  override val history: HybridHistory = generateHistory
  override val mempool: SimpleBoxTransactionMemPool = SimpleBoxTransactionMemPool.emptyPool
  override val wallet = (0 until 100).foldLeft(HWallet.readOrGenerate(settings, "p"))((w, _) => w.generateNewSecret())
  override val state: HBoxStoredState = HBoxStoredState.readOrGenerate(settings)

  //Generators
  override val transactionGenerator: Gen[SimpleBoxTransaction] = simpleBoxTransactionGen

  override val stateChangesGenerator: Gen[BoxStateChanges[PublicKey25519Proposition, PublicKey25519NoncedBox]] =
    stateChangesGen

  override def genValidModifier(curHistory: HybridHistory, mempoolTransactionFetchOption: Boolean, noOfTransactionsFromMempool: Int): HybridBlock = {

    if (curHistory.pairCompleted) {
      for {
        timestamp: Long <- positiveLongGen
        nonce: Long <- positiveLongGen
        brothersCount: Byte <- positiveByteGen
        proposition: PublicKey25519Proposition <- propositionGen
        brothers <- Gen.listOfN(brothersCount, powHeaderGen)
      } yield {
        val brotherBytes = PowBlockCompanion.brotherBytes(brothers)
        val brothersHash: Array[Byte] = Blake2b256(brotherBytes)
        new PowBlock(curHistory.bestPowId, curHistory.bestPosId, timestamp, nonce, brothersCount, brothersHash, proposition, brothers)
      }
    } else {
      if (mempoolTransactionFetchOption) {
        for {
          timestamp: Long <- positiveLongGen
          txs = simpleMempoolTransactionGen(noOfTransactionsFromMempool)
          box: PublicKey25519NoncedBox <- noncedBoxGen
          attach: Array[Byte] <- genBoundedBytes(0, 4096)
          generator: PrivateKey25519 <- key25519Gen.map(_._1)
        } yield PosBlock.create(curHistory.bestPowId, timestamp, txs, box.copy(proposition = generator.publicImage), attach, generator)
      }
      else {
        for {
          timestamp: Long <- positiveLongGen
          txs: Seq[SimpleBoxTransaction] <- smallInt.flatMap(txNum => Gen.listOfN(txNum, simpleBoxTransactionGen))
          box: PublicKey25519NoncedBox <- noncedBoxGen
          attach: Array[Byte] <- genBoundedBytes(0, 4096)
          generator: PrivateKey25519 <- key25519Gen.map(_._1)
        } yield PosBlock.create(curHistory.bestPowId, timestamp, txs, box.copy(proposition = generator.publicImage), attach, generator)
      }
    }
  }.apply(Gen.Parameters.default, Seed.random()).get


  override def genValidTransactionPair(curHistory: HybridHistory): Seq[SimpleBoxTransaction] = {
    val keys = key25519Gen.apply(Gen.Parameters.default, Seed.random()).get
    val value = positiveLongGen.apply(Gen.Parameters.default, Seed.random()).get

    val newBox: IndexedSeq[(PublicKey25519Proposition, Long)] = IndexedSeq((keys._2, value))
    val trx: SimpleBoxTransaction = simpleBoxTransactionGenCustomMakeBoxes(newBox).apply(Gen.Parameters.default, Seed.random()).get
    val useBox: IndexedSeq[(PrivateKey25519, Long)] = IndexedSeq((keys._1, trx.newBoxes.toVector(0).nonce))

    var trxnPair = Seq[SimpleBoxTransaction]()
    trxnPair = trxnPair :+ trx
    trxnPair = trxnPair :+ simpleBoxTransactionGenCustomUseBoxes(useBox).apply(Gen.Parameters.default, Seed.random()).get

    trxnPair
  }

  override def genValidModifierCustomTransactions(curHistory: HybridHistory, trx: SimpleBoxTransaction): HybridBlock = {
    if (curHistory.pairCompleted) {
      for {
        timestamp: Long <- positiveLongGen
        nonce: Long <- positiveLongGen
        brothersCount: Byte <- positiveByteGen
        proposition: PublicKey25519Proposition <- propositionGen
        brothers <- Gen.listOfN(brothersCount, powHeaderGen)
      } yield {
        val brotherBytes = PowBlockCompanion.brotherBytes(brothers)
        val brothersHash: Array[Byte] = Blake2b256(brotherBytes)
        new PowBlock(curHistory.bestPowId, curHistory.bestPosId, timestamp, nonce, brothersCount, brothersHash, proposition, brothers)
      }
    } else {
      for {
        timestamp: Long <- positiveLongGen
        txs: Seq[SimpleBoxTransaction] = Seq(trx)
        box: PublicKey25519NoncedBox <- noncedBoxGen
        attach: Array[Byte] <- genBoundedBytes(0, 4096)
        generator: PrivateKey25519 <- key25519Gen.map(_._1)
      } yield PosBlock.create(curHistory.bestPowId, timestamp, txs, box.copy(proposition = generator.publicImage), attach, generator)
    }
  }.apply(Gen.Parameters.default, Seed.random()).get


  def simpleMempoolTransactionGen(noOfTransactionsFromMempool: Int): Seq[SimpleBoxTransaction] = {
    var a = 0
    var txs = Seq[SimpleBoxTransaction]()
    (1 until noOfTransactionsFromMempool) foreach { _ =>
      val p = mempool.take(mempool.size - 1).toVector({
        Random.nextInt(mempool.size - 1)
      })
      mempool.remove(p)
      txs = txs :+ p
    }
    txs
  }
}

