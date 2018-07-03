package network.elrond.benchmark;

import java.io.Serializable;

public class ElrondSystemTimerImpl implements ElrondSystemTimer, Serializable {
    @Override
    public long getCurrentTime() {
        return System.currentTimeMillis();
    }
}
