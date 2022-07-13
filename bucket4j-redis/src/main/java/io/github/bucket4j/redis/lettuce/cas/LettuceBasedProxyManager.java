/*-
 * ========================LICENSE_START=================================
 * Bucket4j
 * %%
 * Copyright (C) 2015 - 2022 Vladimir Bukhtoyarov
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */

package io.github.bucket4j.redis.lettuce.cas;

import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.distributed.proxy.generic.compare_and_swap.AbstractCompareAndSwapBasedProxyManager;
import io.github.bucket4j.distributed.proxy.generic.compare_and_swap.AsyncCompareAndSwapOperation;
import io.github.bucket4j.distributed.proxy.generic.compare_and_swap.CompareAndSwapOperation;
import io.github.bucket4j.distributed.remote.RemoteBucketState;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class LettuceBasedProxyManager extends AbstractCompareAndSwapBasedProxyManager<byte[]> {

    private final RedisAsyncCommands<byte[], byte[]> commands;
    private final long keepAfterRefillDurationMillis;

    public LettuceBasedProxyManager(RedisAsyncCommands<byte[], byte[]> redisAsyncCommands, ClientSideConfig clientSideConfig, Duration keepAfterRefillDuration) {
        super(clientSideConfig);
        Objects.requireNonNull(redisAsyncCommands);
        this.commands = redisAsyncCommands;
        this.keepAfterRefillDurationMillis = keepAfterRefillDuration.toMillis();
    }

    /**
     *
     * @param redisAsyncCommands
     * @param keepAfterRefillDuration specifies how long bucket should be held in the cache after all consumed tokens have been refilled.
     */
    public LettuceBasedProxyManager(RedisAsyncCommands<byte[], byte[]> redisAsyncCommands, Duration keepAfterRefillDuration) {
        this(redisAsyncCommands, ClientSideConfig.getDefault(), keepAfterRefillDuration);
    }

    /**
     *
     * @param statefulRedisConnection
     * @param clientSideConfig
     * @param keepAfterRefillDuration specifies how long bucket should be held in the cache after all consumed tokens have been refilled.
     */
    public LettuceBasedProxyManager(StatefulRedisConnection<byte[], byte[]> statefulRedisConnection, ClientSideConfig clientSideConfig, Duration keepAfterRefillDuration) {
        this(statefulRedisConnection.async(), clientSideConfig, keepAfterRefillDuration);
    }

    /**
     *
     * @param statefulRedisConnection
     * @param keepAfterRefillDuration specifies how long bucket should be held in the cache after all consumed tokens have been refilled.
     */
    public LettuceBasedProxyManager(StatefulRedisConnection<byte[], byte[]> statefulRedisConnection, Duration keepAfterRefillDuration) {
        this(statefulRedisConnection, ClientSideConfig.getDefault(), keepAfterRefillDuration);
    }

    /**
     *
     * @param redisClient
     * @param keepAfterRefillDuration specifies how long bucket should be held in the cache after all consumed tokens have been refilled.
     */
    public LettuceBasedProxyManager(RedisClient redisClient, Duration keepAfterRefillDuration) {
        this(redisClient, ClientSideConfig.getDefault(), keepAfterRefillDuration);
    }

    /**
     *
     * @param redisClient
     * @param clientSideConfig
     * @param keepAfterRefillDuration specifies how long bucket should be held in the cache after all consumed tokens have been refilled.
     */
    public LettuceBasedProxyManager(RedisClient redisClient, ClientSideConfig clientSideConfig, Duration keepAfterRefillDuration) {
        this(redisClient.connect(ByteArrayCodec.INSTANCE), clientSideConfig, keepAfterRefillDuration);
    }

    @Override
    protected CompareAndSwapOperation beginCompareAndSwapOperation(byte[] key) {
        byte[][] keys = {key};
        return new CompareAndSwapOperation() {
            @Override
            public Optional<byte[]> getStateData() {
                RedisFuture<byte[]> stateFuture = commands.get(key);
                return Optional.ofNullable(getFutureValue(stateFuture));
            }

            @Override
            public boolean compareAndSwap(byte[] originalData, byte[] newData, RemoteBucketState newState) {
                return getFutureValue(compareAndSwapFuture(key, keys, originalData, newData, newState));
            }
        };
    }

    @Override
    protected AsyncCompareAndSwapOperation beginAsyncCompareAndSwapOperation(byte[] key) {
        byte[][] keys = {key};
        return new AsyncCompareAndSwapOperation() {
            @Override
            public CompletableFuture<Optional<byte[]>> getStateData() {
                RedisFuture<byte[]> stateFuture = commands.get(key);
                return convertToCompletableFuture(stateFuture)
                        .thenApply((byte[] resultBytes) -> Optional.ofNullable(resultBytes));
            }

            @Override
            public CompletableFuture<Boolean> compareAndSwap(byte[] originalData, byte[] newData, RemoteBucketState newState) {
                return convertToCompletableFuture(compareAndSwapFuture(key, keys, originalData, newData, newState));
            }
        };
    }

    @Override
    public void removeProxy(byte[] key) {
        RedisFuture<?> future = commands.del(key);
        getFutureValue(future);
    }

    @Override
    protected CompletableFuture<Void> removeAsync(byte[] key) {
        RedisFuture<?> future = commands.del(key);
        return convertToCompletableFuture(future).thenApply(bytes -> null);
    }

    @Override
    public boolean isAsyncModeSupported() {
        return true;
    }

    private RedisFuture<Boolean> compareAndSwapFuture(byte[] key, byte[][] keys, byte[] originalData, byte[] newData, RemoteBucketState newState) {
        if (originalData == null) {
            // nulls are prohibited as values, so "replace" must not be used in such cases
            String script = "return redis.call('set', KEYS[1], ARGV[1], 'nx', 'px', ARGV[2])";
            byte[][] params = {newData, encodeLong(calculateTtlMillis(newState))};
            return commands.eval(script, ScriptOutputType.BOOLEAN, keys, params);
        } else {
            String script =
                    "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                            "redis.call('psetex', KEYS[1], ARGV[3], ARGV[2]); " +
                            "return 1; " +
                        "else " +
                            "return 0; " +
                        "end";
            byte[][] params = {originalData, newData, encodeLong(calculateTtlMillis(newState))};
            return commands.eval(script, ScriptOutputType.BOOLEAN, keys, params);
        }
    }

    private <T> CompletableFuture<T> convertToCompletableFuture(RedisFuture<T> redissonFuture) {
        CompletableFuture<T> jdkFuture = new CompletableFuture<>();
        redissonFuture.whenComplete((result, error) -> {
            if (error != null) {
                jdkFuture.completeExceptionally(error);
            } else {
                jdkFuture.complete(result);
            }
        });
        return jdkFuture;
    }

    private <V> V getFutureValue(RedisFuture<V> value) {
        try {
            return value.get();
        } catch (InterruptedException e) {
            value.cancel(true);
            Thread.currentThread().interrupt();
            throw new RedisException(e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RedisException ? (RedisException) e.getCause() :
                    new RedisException("Unexpected exception while processing command", e.getCause());
        }
    }

    private byte[] encodeLong(Long value) {
        return ("" + value).getBytes(StandardCharsets.UTF_8);
    }

    private long calculateTtlMillis(RemoteBucketState state) {
        Optional<TimeMeter> clock = getClientSideConfig().getClientSideClock();
        long currentTimeNanos = clock.isPresent() ? clock.get().currentTimeNanos() : System.currentTimeMillis() * 1_000_000;
        long millisToFullRefill = state.calculateFullRefillingTime(currentTimeNanos) / 1_000_000;
        return keepAfterRefillDurationMillis + millisToFullRefill;
    }
}