package network.elrond.data;

import junit.framework.TestCase;
import network.elrond.account.*;
import network.elrond.core.Util;
import network.elrond.crypto.PrivateKey;
import network.elrond.crypto.PublicKey;
import network.elrond.service.AppServiceProvider;
import org.junit.Test;
import org.mapdb.Fun;

import java.math.BigInteger;

public class GenesisBlockTest {

    @Test
    public void testBlock() throws Exception{
        BigInteger value = BigInteger.TEN.pow(10);

        AccountStateService accountStateService = AppServiceProvider.getAccountStateService();
        ExecutionService executionService = AppServiceProvider.getExecutionService();

        PrivateKey pvk1 = new PrivateKey("Another seed in the wall");
        PublicKey pbk1 = new PublicKey(pvk1);
        AccountAddress acRecv = AccountAddress.fromBytes(pbk1.getValue());
        AccountAddress acMint = AccountAddress.fromBytes(Util.PUBLIC_KEY_MINTING.getValue());

        AccountsContext accTemp = new AccountsContext();
        accTemp.setDatabasePath(null);

        Accounts accounts = new Accounts(accTemp, new AccountsPersistenceUnit<>(accTemp.getDatabasePath()));
        AccountState acsMintTest = accountStateService.getAccountState(acMint, accounts);
        TestCase.assertEquals("Expected " + Util.VALUE_MINTING, Util.VALUE_MINTING, acsMintTest.getBalance());

        Fun.Tuple2<Block, Transaction> genesisData = accountStateService.generateGenesisBlock(Util.byteArrayToHexString(pbk1.getValue()), value, accTemp, pvk1);

        TestCase.assertNotNull("Not expecting null for GenesisData ", genesisData);

        accounts = new Accounts(accTemp, new AccountsPersistenceUnit<>(accTemp.getDatabasePath()));
        AccountState acsMint = accountStateService.getAccountState(acMint, accounts);
        AccountState acsRecv = accountStateService.getAccountState(acRecv, accounts);

        TestCase.assertEquals("Expecting null ", null, acsRecv);
        TestCase.assertEquals("Expecting " + Util.VALUE_MINTING, Util.VALUE_MINTING, acsMint.getBalance());

        executionService.processTransaction(genesisData.b, accounts);

        acsMint = accountStateService.getAccountState(acMint, accounts);
        acsRecv = accountStateService.getAccountState(acRecv, accounts);

        TestCase.assertEquals("Expecting " + value.toString(10), value, acsRecv.getBalance());
        TestCase.assertEquals("Expecting " + Util.VALUE_MINTING.subtract(value), Util.VALUE_MINTING.subtract(value), acsMint.getBalance());


        //GenesisBlock gb = new GenesisBlock();
        //System.out.println(AppServiceProvider.getSerializationService().encodeJSON(gb));

    }
}