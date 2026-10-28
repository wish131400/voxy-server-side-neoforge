package dev.xantha.vss.common;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConcurrentPositionSetTest {
    @Test void symmetricCoordinatesRemainDistinctAndIterationSupportsRangePruningAndRetry() throws Exception {
        var set=new ConcurrentPositionSet();var expected=new HashSet<Long>();
        for(int x=-128;x<128;x++)for(int z=-128;z<128;z++) {
            long packed=PositionUtil.packPosition(x,z);set.add(packed);expected.add(packed);
        }
        assertEquals(expected,set);
        var field=ConcurrentPositionSet.class.getDeclaredField("keys");field.setAccessible(true);
        var hashes=new HashMap<Integer,Integer>();
        for(Object key:(Set<?>)field.get(set))hashes.merge(key.hashCode(),1,Integer::sum);
        System.out.printf("POSITION_HASH entries=%d mixedBuckets=%d rawBuckets=%d%n",set.size(),hashes.size(),expected.stream().map(value -> Long.hashCode(value)).distinct().count());
        assertTrue(hashes.size()>60000,"Regular grids must not collapse into X xor Z buckets");
        set.removeIf(p->PositionUtil.isOutOfRange(p,0,0,32));
        expected.removeIf(p->PositionUtil.isOutOfRange(p,0,0,32));assertEquals(expected,set);
        var iter=set.iterator();long removed=iter.next();iter.remove();assertFalse(set.contains(removed));
        set.clear();assertTrue(set.isEmpty());
    }
}
