package org.infinispan.query.distributed;

import static org.infinispan.configuration.cache.IndexStorage.LOCAL_HEAP;
import static org.infinispan.functional.FunctionalTestUtils.await;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.query.Search;
import org.infinispan.query.core.stats.IndexInfo;
import org.infinispan.query.core.stats.QueryStatistics;
import org.infinispan.query.core.stats.SearchStatistics;
import org.infinispan.query.helper.SearchConfig;
import org.infinispan.query.helper.StaticTestingErrorHandler;
import org.infinispan.query.test.Person;
import org.infinispan.query.test.QueryTestSCI;
import org.infinispan.query.test.Transaction;
import org.infinispan.test.MultipleCacheManagersTest;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * @since 12.0
 */
@Test(groups = "functional", testName = "query.distributed.StatsTest")
public class StatsTest extends MultipleCacheManagersTest {

   private Cache<String, Object> cache0;
   private Cache<String, Object> cache1;
   private Cache<String, Object> cache2;
   private QueryStatistics queryStatistics0;
   private QueryStatistics queryStatistics1;
   private QueryStatistics queryStatistics2;
   private String indexedQuery;
   private String nonIndexedQuery;
   private String hybridQuery;

   @Override
   protected void createCacheManagers() throws Throwable {
      ConfigurationBuilder cacheCfg = getDefaultClusteredCacheConfig(CacheMode.DIST_SYNC, false);
      cacheCfg.statistics().enable();
      cacheCfg.indexing().enable()
            .storage(LOCAL_HEAP)
            .addIndexedEntity(Person.class)
            .addIndexedEntity(Transaction.class)
            .addProperty(SearchConfig.ERROR_HANDLER, StaticTestingErrorHandler.class.getName());

      createClusteredCaches(3, QueryTestSCI.INSTANCE, cacheCfg);
      cache0 = cache(0);
      cache1 = cache(1);
      cache2 = cache(2);
      queryStatistics0 = Search.getSearchStatistics(cache0).getQueryStatistics();
      queryStatistics1 = Search.getSearchStatistics(cache1).getQueryStatistics();
      queryStatistics2 = Search.getSearchStatistics(cache2).getQueryStatistics();
      indexedQuery = String.format("From %s where name : 'Donald'", Person.class.getName());
      nonIndexedQuery = String.format("From %s where nonIndexedField = 'first'", Person.class.getName());
      hybridQuery = String.format("From %s where nonIndexedField = 'first' and age > 50", Person.class.getName());
   }

   @BeforeMethod
   public void setUp() {
      cache0.clear();
      addData();
   }

   @Test
   public void testQueryStats() {
      testNonIndexedQueryStats();
      testIndexedQueryStats();
      testHybridQueryStats();
      testClean();
   }

   @Test
   public void testIndexStats() {
      Set<String> expectedEntities = new HashSet<>(Arrays.asList(Person.class.getName(), Transaction.class.getName()));
      int expectDocuments = cacheManagers.size() * cache0.getCacheConfiguration().clustering().hash().numOwners();

      Set<String> totalEntities = new HashSet<>();
      int totalCount = 0;
      for (int i = 0; i < cacheManagers.size(); i++) {
         SearchStatistics searchStatistics = Search.getSearchStatistics(cache(i));
         Map<String, IndexInfo> indexInfos = searchStatistics.getIndexStatistics().indexInfos();
         totalEntities.addAll(indexInfos.keySet());
         for (IndexInfo indexInfo : indexInfos.values()) {
            totalCount += indexInfo.count();
         }
      }
      assertEquals(totalEntities, expectedEntities);
      assertEquals(totalCount, expectDocuments);

      SearchStatistics clusteredStats = await(Search.getClusteredSearchStatistics(cache0));
      Map<String, IndexInfo> classIndexInfoMap = clusteredStats.getIndexStatistics().indexInfos();
      assertEquals(classIndexInfoMap.keySet(), expectedEntities);
      Long reduce = classIndexInfoMap.values().stream().map(IndexInfo::count).reduce(0L, Long::sum);
      int i = cacheManagers.size() * cache0.getCacheConfiguration().clustering().hash().numOwners();
      assertEquals(reduce.intValue(), i);

   }

   private void testClean() {
      queryStatistics0.clear();
      queryStatistics1.clear();
      queryStatistics2.clear();

      SearchStatistics clustered = await(Search.getClusteredSearchStatistics(cache0));
      QueryStatistics localQueryStatistics = clustered.getQueryStatistics();
      assertEquals(localQueryStatistics.getNonIndexedQueryCount(), 0);
      assertEquals(localQueryStatistics.getHybridQueryCount(), 0);
      assertEquals(localQueryStatistics.getDistributedIndexedQueryCount(), 0);
      assertEquals(localQueryStatistics.getLocalIndexedQueryCount(), 0);
   }

   private void testNonIndexedQueryStats() {
      executeQuery(nonIndexedQuery, cache0);

      assertEquals(queryStatistics0.getNonIndexedQueryCount(), 1);
      assertEquals(queryStatistics1.getNonIndexedQueryCount(), 0);
      assertEquals(queryStatistics2.getNonIndexedQueryCount(), 0);
      SearchStatistics clustered1 = await(Search.getClusteredSearchStatistics(cache1));
      assertEquals(clustered1.getQueryStatistics().getNonIndexedQueryCount(), 1);

      executeQuery(nonIndexedQuery, cache1);

      assertEquals(queryStatistics0.getNonIndexedQueryCount(), 1);
      assertEquals(queryStatistics1.getNonIndexedQueryCount(), 1);
      assertEquals(queryStatistics2.getNonIndexedQueryCount(), 0);
      SearchStatistics clustered2 = await(Search.getClusteredSearchStatistics(cache2));
      assertEquals(clustered2.getQueryStatistics().getNonIndexedQueryCount(), 2);

      executeQuery(nonIndexedQuery, cache2);

      assertEquals(queryStatistics0.getNonIndexedQueryCount(), 1);
      assertEquals(queryStatistics1.getNonIndexedQueryCount(), 1);
      assertEquals(queryStatistics2.getNonIndexedQueryCount(), 1);
      SearchStatistics clustered0 = await(Search.getClusteredSearchStatistics(cache0));
      assertEquals(clustered0.getQueryStatistics().getNonIndexedQueryCount(), 3);
   }

   private void testIndexedQueryStats() {
      executeQuery(indexedQuery, cache0);

      assertEquals(queryStatistics0.getLocalIndexedQueryCount(), 1);
      assertEquals(queryStatistics0.getDistributedIndexedQueryCount(), 1);

      assertEquals(queryStatistics1.getLocalIndexedQueryCount(), 1);
      assertEquals(queryStatistics1.getDistributedIndexedQueryCount(), 0);

      assertEquals(queryStatistics2.getLocalIndexedQueryCount(), 1);
      assertEquals(queryStatistics2.getDistributedIndexedQueryCount(), 0);

      SearchStatistics clustered = await(Search.getClusteredSearchStatistics(cache1));
      assertEquals(clustered.getQueryStatistics().getLocalIndexedQueryCount(), 3);
      assertEquals(clustered.getQueryStatistics().getDistributedIndexedQueryCount(), 1);

      executeQuery(indexedQuery, cache1);

      assertEquals(queryStatistics0.getLocalIndexedQueryCount(), 2);
      assertEquals(queryStatistics0.getDistributedIndexedQueryCount(), 1);

      assertEquals(queryStatistics1.getLocalIndexedQueryCount(), 2);
      assertEquals(queryStatistics1.getDistributedIndexedQueryCount(), 1);

      assertEquals(queryStatistics2.getLocalIndexedQueryCount(), 2);
      assertEquals(queryStatistics2.getDistributedIndexedQueryCount(), 0);

      clustered = await(Search.getClusteredSearchStatistics(cache1));
      assertEquals(clustered.getQueryStatistics().getLocalIndexedQueryCount(), 6);
      assertEquals(clustered.getQueryStatistics().getDistributedIndexedQueryCount(), 2);

      executeQuery(indexedQuery, cache2);

      assertEquals(queryStatistics0.getLocalIndexedQueryCount(), 3);
      assertEquals(queryStatistics0.getDistributedIndexedQueryCount(), 1);

      assertEquals(queryStatistics1.getLocalIndexedQueryCount(), 3);
      assertEquals(queryStatistics1.getDistributedIndexedQueryCount(), 1);

      assertEquals(queryStatistics2.getLocalIndexedQueryCount(), 3);
      assertEquals(queryStatistics2.getDistributedIndexedQueryCount(), 1);

      clustered = await(Search.getClusteredSearchStatistics(cache1));
      assertEquals(clustered.getQueryStatistics().getLocalIndexedQueryCount(), 9);
      assertEquals(clustered.getQueryStatistics().getDistributedIndexedQueryCount(), 3);
   }

   private void testHybridQueryStats() {
      executeQuery(hybridQuery, cache0);

      assertEquals(queryStatistics0.getHybridQueryCount(), 1);
      assertEquals(queryStatistics0.getLocalIndexedQueryCount(), 4);
      assertEquals(queryStatistics0.getDistributedIndexedQueryCount(), 2);

      assertEquals(queryStatistics1.getHybridQueryCount(), 0);
      assertEquals(queryStatistics1.getLocalIndexedQueryCount(), 4);
      assertEquals(queryStatistics1.getDistributedIndexedQueryCount(), 1);

      assertEquals(queryStatistics2.getHybridQueryCount(), 0);
      assertEquals(queryStatistics2.getLocalIndexedQueryCount(), 4);
      assertEquals(queryStatistics2.getDistributedIndexedQueryCount(), 1);

      SearchStatistics clustered = await(Search.getClusteredSearchStatistics(cache1));
      assertEquals(clustered.getQueryStatistics().getHybridQueryCount(), 1);
      assertEquals(clustered.getQueryStatistics().getLocalIndexedQueryCount(), 12);
      assertEquals(clustered.getQueryStatistics().getDistributedIndexedQueryCount(), 4);

      executeQuery(hybridQuery, cache1);

      assertEquals(queryStatistics0.getHybridQueryCount(), 1);
      assertEquals(queryStatistics0.getLocalIndexedQueryCount(), 5);
      assertEquals(queryStatistics0.getDistributedIndexedQueryCount(), 2);

      assertEquals(queryStatistics1.getHybridQueryCount(), 1);
      assertEquals(queryStatistics1.getLocalIndexedQueryCount(), 5);
      assertEquals(queryStatistics1.getDistributedIndexedQueryCount(), 2);

      assertEquals(queryStatistics2.getHybridQueryCount(), 0);
      assertEquals(queryStatistics2.getLocalIndexedQueryCount(), 5);
      assertEquals(queryStatistics2.getDistributedIndexedQueryCount(), 1);

      clustered = await(Search.getClusteredSearchStatistics(cache1));
      assertEquals(clustered.getQueryStatistics().getHybridQueryCount(), 2);
      assertEquals(clustered.getQueryStatistics().getLocalIndexedQueryCount(), 15);
      assertEquals(clustered.getQueryStatistics().getDistributedIndexedQueryCount(), 5);

      executeQuery(hybridQuery, cache2);

      assertEquals(queryStatistics0.getHybridQueryCount(), 1);
      assertEquals(queryStatistics0.getLocalIndexedQueryCount(), 6);
      assertEquals(queryStatistics0.getDistributedIndexedQueryCount(), 2);

      assertEquals(queryStatistics1.getHybridQueryCount(), 1);
      assertEquals(queryStatistics1.getLocalIndexedQueryCount(), 6);
      assertEquals(queryStatistics1.getDistributedIndexedQueryCount(), 2);

      assertEquals(queryStatistics2.getHybridQueryCount(), 1);
      assertEquals(queryStatistics2.getLocalIndexedQueryCount(), 6);
      assertEquals(queryStatistics2.getDistributedIndexedQueryCount(), 2);

      clustered = await(Search.getClusteredSearchStatistics(cache1));
      assertEquals(clustered.getQueryStatistics().getHybridQueryCount(), 3);
      assertEquals(clustered.getQueryStatistics().getLocalIndexedQueryCount(), 18);
      assertEquals(clustered.getQueryStatistics().getDistributedIndexedQueryCount(), 6);
   }

   private void executeQuery(String q, Cache<String, Object> fromCache) {
      List<Person> list = Search.getQueryFactory(fromCache).<Person>create(q).execute().list();
      assertFalse(list.isEmpty());
   }

   private void addData() {
      Person person1 = new Person("Donald", "Duck", 86);
      person1.setNonIndexedField("second");
      Person person2 = new Person("Mickey", "Mouse", 92);
      person2.setNonIndexedField("first");
      cache0.put("1", person1);
      cache0.put("2", person2);
      cache0.put("3", new Transaction(12, "sss"));
   }

}
