package network.elrond.p2p;

import net.tomp2p.dht.PeerDHT;
import net.tomp2p.dht.PutBuilder;
import net.tomp2p.dht.RemoveBuilder;
import net.tomp2p.futures.BaseFuture;
import net.tomp2p.p2p.AutomaticFuture;
import net.tomp2p.p2p.JobScheduler;
import net.tomp2p.p2p.Shutdown;
import net.tomp2p.peers.Number160;
import net.tomp2p.storage.Data;

import java.io.IOException;

/**
 * Example of direct replication with put and remove.
 *
 * @author Thomas Bocek
 *
 */
public final class ExampleDirectReplication {

    private static final int NINE_SECONDS = 9 * 1000;

    /**
     * Empty constructor.
     */
    private ExampleDirectReplication() {
    }

    /**
     * Create 100 peers and start the example.
     *
     * @param args
     *            Empty
     * @throws Exception .
     */
    public static void main(final String[] args) throws Exception {
        PeerDHT[] peers = null;
        try {
            final int nrPeers = 100;
            final int port = 4001;
            peers = ExampleUtils.createAndAttachPeersDHT(nrPeers, port);
            ExampleUtils.bootstrap(peers);
            exampleDirectReplication(peers);
        } finally {
            if (peers != null && peers[0] != null) {
                peers[0].shutdown();
            }
        }
    }

    /**
     * The example first stores data and pushed it a couple of times using direct replication. After, it removes the
     * content, calling remove twice.
     *
     * @param peers
     *            The peers in this P2P network
     * @throws InterruptedException
     * @throws IOException .
     */
    private static void exampleDirectReplication(final PeerDHT[] peers) throws IOException, InterruptedException {
        PutBuilder putBuilder = peers[1].put(Number160.ONE).data(new Data("test"));
        JobScheduler replication = new JobScheduler(peers[1].peer());
        Shutdown shutdown = replication.start(putBuilder, 1000, -1, new AutomaticFuture() {
            @Override
            public void futureCreated(BaseFuture future) {
                System.out.println("put again...");
            }
        });
        Thread.sleep(NINE_SECONDS);
        System.out.println("stop replication");
        shutdown.shutdown();
        RemoveBuilder removeBuilder = peers[1].remove(Number160.ONE);
        replication.start(removeBuilder, 1000, 9, new AutomaticFuture() {
            @Override
            public void futureCreated(BaseFuture future) {
                System.out.println("remove again...");
            }
        });
        Thread.sleep(NINE_SECONDS);
        System.out.println("done");
        replication.shutdown().awaitUninterruptibly();
    }
}