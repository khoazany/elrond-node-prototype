package network.elrond.data;

import network.elrond.account.Accounts;
import network.elrond.account.AccountsManager;
import network.elrond.blockchain.Blockchain;
import network.elrond.blockchain.BlockchainService;
import network.elrond.blockchain.BlockchainUnitType;
import network.elrond.chronology.ChronologyService;
import network.elrond.chronology.Round;
import network.elrond.core.Util;
import network.elrond.crypto.MultiSignatureService;
import network.elrond.service.AppServiceProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongycastle.util.encoders.Base64;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ExecutionServiceImpl implements ExecutionService {
    private static final Logger logger = LogManager.getLogger(ExecutionServiceImpl.class);

    private SerializationService serializationService = AppServiceProvider.getSerializationService();

    @Override
    public ExecutionReport processBlock(Block block, Accounts accounts, Blockchain blockchain) {
        logger.traceEntry("params: {} {} {}", block, accounts, blockchain);

        Util.check(block != null, "block != null");
        Util.check(accounts != null, "accounts != null");
        Util.check(blockchain != null, "blockchain != null");

        try {
            return logger.traceExit(_processBlock(accounts, blockchain, block));
        } catch (IOException | ClassNotFoundException e) {
            return logger.traceExit(ExecutionReport.create().ko(e));
        }
    }

    private boolean validateBlockSigners(Accounts accounts, Blockchain blockchain, Block block) {
        logger.traceEntry("params: {} {} {}", accounts, blockchain, block);
        // TODO: need to check that signers are the right ones for that specific epoch & round
        // Signers part of the eligible list in the epoch
        // Signers are selected by the previous block signature
        return logger.traceExit(true);
    }

    private boolean validateBlockSignature(ArrayList<String> signers, Block block) {
        logger.traceEntry("params: {} {}", signers, block);
        // TODO: validate the multi-signature for the participating signers
        MultiSignatureService signatureService = AppServiceProvider.getMultiSignatureService();
        ArrayList<byte[]> signersPublicKeys = new ArrayList<>();
        byte[] signature = block.getSignature();
        byte[] commitment = block.getCommitment();
        block.setSignature(null);
        block.setCommitment(null);
        byte[] message = AppServiceProvider.getSerializationService().getHash(block);
        long bitmap = (1 << signers.size()) - 1;

        block.setSignature(signature);
        block.setCommitment(commitment);

        for (String signer : signers) {
            signersPublicKeys.add(Util.hexStringToByteArray(signer));
        }

        return logger.traceExit(signatureService.verifyAggregatedSignature(signersPublicKeys, signature, commitment, message, bitmap));
    }

    private boolean validateBlockTimestampRound(Blockchain blockchain, Block block){
        logger.traceEntry("params: {} {}", blockchain, block);
        Util.check(blockchain.getGenesisBlock() != null, "genesis!=null");

        ChronologyService chronologyService = AppServiceProvider.getChronologyService();

        Round roundBlock = new Round();

        long timestamp = blockchain.getGenesisBlock().getTimestamp();
        long roundTimeDuration = chronologyService.getRoundTimeDuration();
        long roundIndex = block.getRoundIndex();

        long startTimeStamp = timestamp + roundIndex * roundTimeDuration;
        roundBlock.setStartTimeStamp(startTimeStamp);
        logger.trace("Computed round = {}, block's timestamp = {}, block's round = {}, genesis timestamp = {}",
                roundBlock, block.getTimestamp(), block.getRoundIndex(), timestamp);

        return logger.traceExit(chronologyService.isDateTimeInRound(roundBlock, block.getTimestamp()));
    }

    private ExecutionReport _processBlock(Accounts accounts, Blockchain blockchain, Block block) throws IOException, ClassNotFoundException {
        logger.traceEntry("params: {} {}", accounts, blockchain, block);
        ExecutionReport blockExecutionReport = ExecutionReport.create();
        BlockchainService blockchainService = AppServiceProvider.getBlockchainService();
        ArrayList<String> signers;
        String blockHash = serializationService.getHashString(block);

        // check that block is not already processed
//        if (blockchainService.contains(blockHash, blockchain, BlockchainUnitType.BLOCK)) {
//            blockExecutionReport.ko("Block already in blockchain");
//            return blockExecutionReport;
//        }

        // check if previous block hash is in blockchain, otherwise can't add it yet
        // do the check only if nonce is not 0
        if (!block.getNonce().equals(BigInteger.ZERO) &&
                !blockchainService.contains(Util.getDataEncoded64(block.getPrevBlockHash()), blockchain, BlockchainUnitType.BLOCK)) {

            blockExecutionReport.ko("Previous block not in blockchain");
            logger.trace("Block process FAILED!");
            return logger.traceExit(blockExecutionReport);
        }

        //check if the current block is genesis and save it in blockchain structure
        if (block.getNonce().equals(BigInteger.ZERO)){
            logger.trace("The current block is genesis and we save it in blockchain structure");
            blockchain.setGenesisBlock(block);
        }

        //check genesis block existence
        if (blockchain.getGenesisBlock() == null){
            blockExecutionReport.ko("Genesis block missing!");
            logger.trace("Block process FAILED!");
            return logger.traceExit(blockExecutionReport);
        }

        //check timestamp and round
        if (!validateBlockTimestampRound(blockchain, block)){
            blockExecutionReport.ko(String.format("Timestamp and round mismatch! Block nonce: %d, round index: %d, timestamp %d, genesis timestamp: %d",
                    block.getNonce().longValue(), block.roundIndex, block.getTimestamp(), blockchain.getGenesisBlock().getTimestamp()));
            logger.trace("Block process FAILED!");
            return logger.traceExit(blockExecutionReport);
        }

        // check block signers are valid for the round
        if (!validateBlockSigners(accounts, blockchain, block)) {
            blockExecutionReport.ko("Signers not ok for epoch/round");
            logger.trace("Block process FAILED!");
            return logger.traceExit(blockExecutionReport);
        }

        // get signature parts from block
        signers = (ArrayList<String>) block.getListPublicKeys();

        // check multi-signature is valid
        if (!validateBlockSignature(signers, block)) {
            blockExecutionReport.ko("Signature not valid");
            logger.trace("Block process FAILED!");
            return logger.traceExit(blockExecutionReport);
        }

        // TODO: split the block processing for the two usecases
        // there are two usecases for processing blocks
        // 1. when part of the consensus group, the node needs to validate and sign the block and add it to it's blockchain if pBFT OK otherwise rollback
        // 2. when not part of the consensus validate block and add it to it's blockchain if valid

        logger.trace("Processing transactions...");
        List<Transaction> transactions = AppServiceProvider.getTransactionService().getTransactions(blockchain, block);
        for (Transaction transaction : transactions) {
            ExecutionReport transactionExecutionReport = processTransaction(transaction, accounts);
            if (!transactionExecutionReport.isOk()) {
                blockExecutionReport.combine(transactionExecutionReport);
                logger.trace("Block process FAILED!");
                break;
            }
        }

        if (blockExecutionReport.isOk()) {
            // check state merkle patricia trie root is the same with what was stored in block
            if (!Arrays.equals(block.getAppStateHash(), accounts.getAccountsPersistenceUnit().getRootHash())) {
                blockExecutionReport.ko(String.format("Application state root hash does not match. Generated: %s, block: %s",
                        Util.getDataEncoded64(accounts.getAccountsPersistenceUnit().getRootHash()),
                        Util.getDataEncoded64(block.getAppStateHash())));
                AppServiceProvider.getAccountStateService().rollbackAccountStates(accounts);
                logger.trace("Block process FAILED!");
                return logger.traceExit(blockExecutionReport);
            }

            AppServiceProvider.getAccountStateService().commitAccountStates(accounts);
            blockExecutionReport.ok(String.format("Commit account state changes, state root hash: %s", Util.getDataEncoded64(accounts.getAccountsPersistenceUnit().getRootHash())));
            logger.trace("Block process was SUCCESSFUL!");
        } else {
            AppServiceProvider.getAccountStateService().rollbackAccountStates(accounts);
            blockExecutionReport.ko("Rollback account state changes");
            logger.trace("Block process FAILED!");
        }

        return logger.traceExit(blockExecutionReport);
    }

    @Override
    public ExecutionReport processTransaction(Transaction transaction, Accounts accounts) {
        logger.traceEntry("params: {} {}", transaction, accounts);
        Util.check(transaction != null, "transaction != null");
        Util.check(accounts != null, "accounts != null");

        try {
            return logger.traceExit(_processTransaction(accounts, transaction));
        } catch (Exception e) {
            return logger.traceExit(ExecutionReport.create().ko(e));
        }
    }

    private ExecutionReport _processTransaction(Accounts accounts, Transaction transaction) throws IOException, ClassNotFoundException {
        logger.traceEntry("params: {} {}", transaction, accounts);

        Util.check(transaction != null, "Null transaction");

        String strHash = new String(Base64.encode(serializationService.getHash(transaction)));

        if (!AppServiceProvider.getTransactionService().verifyTransaction(transaction)) {
            return logger.traceExit(ExecutionReport.create().ko("Invalid transaction! tx hash: " + strHash));
        }

        //We have to copy-construct the objects for sandbox mode
        if (!AccountsManager.instance().hasFunds(accounts, transaction.getSendAddress(), transaction.getValue())) {
            return logger.traceExit(ExecutionReport.create().ko("Invalid transaction! Will result in negative balance! tx hash: " + strHash));
        }

        if (!AccountsManager.instance().hasCorrectNonce(accounts, transaction.getSendAddress(), transaction.getNonce())) {
            return logger.traceExit(ExecutionReport.create().ko("Invalid transaction! Nonce mismatch! tx hash: " + strHash));
        }

        AccountsManager.instance().transferFunds(accounts,
                transaction.getSendAddress(), transaction.getReceiverAddress(),
                transaction.getValue(), transaction.getNonce());

        return logger.traceExit(ExecutionReport.create().ok());
    }
}
