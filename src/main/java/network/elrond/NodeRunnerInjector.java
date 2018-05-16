package network.elrond;

import net.tomp2p.dht.FuturePut;
import network.elrond.application.AppContext;
import network.elrond.application.AppState;
import network.elrond.core.Util;
import network.elrond.crypto.PrivateKey;
import network.elrond.crypto.PublicKey;
import network.elrond.data.*;
import network.elrond.p2p.P2PBroadcastChanel;
import network.elrond.service.AppServiceProvider;
import org.bouncycastle.util.encoders.Base64;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.Random;
import java.util.Scanner;

public class NodeRunnerInjector {

    private static TransactionService ts = AppServiceProvider.getTransactionService();
    private static BlockService blks = AppServiceProvider.getBlockService();

    public static void main(String[] args) throws Exception {

        AppContext context = new AppContext();
        context.setMasterPeerIpAddress("127.0.0.1");
        context.setMasterPeerPort(4000);
        context.setPort(4001);
        context.setPeerId(0);


        context.setEmitter(true);
        Application app = new Application(context);
        app.start();

        PrivateKey pvKey = new PrivateKey();
        PublicKey pbKey = new PublicKey(pvKey);

        Random rdm = new Random(10);

        Thread thread = new Thread(() -> {

            AppState state = app.getState();

            do {
                InjectTx(state, rdm, pbKey, pvKey);
                InjectBlk(state, rdm);

                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            } while (state.isStillRunning());

        });
        thread.start();


        @SuppressWarnings("resource")
        Scanner input = new Scanner(System.in);
        while (input.hasNext()) {
            if (input.nextLine().equals("exit")) {
                app.stop();
            }
        }

    }

    private static void InjectTx(AppState state, Random rdm, PublicKey pbKey, PrivateKey pvKey) {
        P2PBroadcastChanel channel = state.getChanel("TRANSACTIONS");

        Transaction tx = new Transaction();

        byte[] buff = new byte[5];
        rdm.nextBytes(buff);

        tx.setData(buff);
        tx.setPubKey(Util.byteArrayToHexString(pbKey.getEncoded()));
        tx.setSendAddress(Util.getAddressFromPublicKey(pbKey.getEncoded()));
        tx.setRecvAddress("0x0000000000000000000000000000000000000000");
        tx.setNonce(BigInteger.ZERO);
        tx.setValue(BigInteger.TEN.pow(8)); //1 ERD

        ts.signTransaction(tx, pvKey.getValue().toByteArray());

        String strHash = new String(Base64.encode(ts.getHash(tx, true)));

        try {
            FuturePut fp = AppServiceProvider.getP2PObjectService().put(channel.getConnection(), strHash, ts.encodeJSON(tx, true));
            if (fp.isSuccess()) {
                LoggerFactory.getLogger(NodeRunnerInjector.class).info("Put tx hash: " + strHash);
                AppServiceProvider.getP2PBroadcastService().publishToChannel(channel, "H:" + strHash);
                //System.out.println(NodeProducerTX.class + " INFO Put tx hash: " + strHash);
                //
            } else {
                //System.out.println(NodeProducerTX.class + " INFO Put tx hash: " + strHash);
                LoggerFactory.getLogger(NodeRunnerInjector.class).error("Error placing tx! hash: " + strHash + "");
            }

        } catch (Exception ex) {

        }
    }

    private static void InjectBlk(AppState state, Random rdm) {
        P2PBroadcastChanel channel = state.getChanel("BLOCKS");

        Block b = new DataBlock();

        b.setNonce(BigInteger.ONE);
        for (int i = 0; i < 5; i++) {
            byte[] buff = new byte[32];
            rdm.nextBytes(buff);

            b.getListTXHashes().add(buff);
        }

        String strHash = new String(Base64.encode(blks.getHash(b, true)));

        try {
            FuturePut fp = AppServiceProvider.getP2PObjectService().put(channel.getConnection(), strHash,
                    blks.encodeJSON(b, true));
            if (fp.isSuccess()) {
                LoggerFactory.getLogger(NodeRunnerInjector.class).info("Put blk hash: " + strHash);
                AppServiceProvider.getP2PBroadcastService().publishToChannel(channel, "H:" + strHash);
                //System.out.println(NodeProducerTX.class + " INFO Put tx hash: " + strHash);
                //
            } else {
                //System.out.println(NodeProducerTX.class + " INFO Put tx hash: " + strHash);
                LoggerFactory.getLogger(NodeRunnerInjector.class).error("Error placing blk! hash: " + strHash + "");
            }

        } catch (Exception ex) {

        }

    }
}
