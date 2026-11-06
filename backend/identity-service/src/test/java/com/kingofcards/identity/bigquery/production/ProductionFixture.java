package com.kingofcards.identity.bigquery.production;
final class ProductionFixture {
 static final java.time.Instant NOW=java.time.Instant.parse("2026-11-01T00:00:00Z");
 static final String BCRYPT="$2a$12$"+"A".repeat(53), HASH="a".repeat(64), CSRF="b".repeat(64), NEXT="c".repeat(64);
}
final class ProductionRace {
 static <T> java.util.List<T> run(java.util.concurrent.Callable<T> first,java.util.concurrent.Callable<T> second)throws Exception {
  try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
   var a=executor.submit(first);var b=executor.submit(second);
   return java.util.List.of(a.get(5,java.util.concurrent.TimeUnit.SECONDS),b.get(5,java.util.concurrent.TimeUnit.SECONDS));
  }
 }
}
