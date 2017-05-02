/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.util.tostring.GridToStringInclude;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.jetbrains.annotations.Nullable;

/**
 *
 */
public class CacheGroupDescriptor {
    /** */
    private final String grpName;

    /** */
    private final int grpId;

    /** */
    private final CacheConfiguration cacheCfg;

    /** */
    private final AffinityTopologyVersion startTopVer;

    /** */
    @GridToStringInclude
    private Map<String, Integer> caches;

    CacheGroupDescriptor(String grpName,
        int grpId,
        CacheConfiguration cacheCfg,
        AffinityTopologyVersion startTopVer,
        Map<String, Integer> caches) {
        assert cacheCfg != null;
        assert grpName != null;
        assert grpId != 0;
        assert startTopVer != null;

        this.grpName = grpName;
        this.grpId = grpId;
        this.cacheCfg = cacheCfg;
        this.startTopVer = startTopVer;
        this.caches = caches;
    }

    void onCacheAdded(String cacheName, int cacheId) {
        assert cacheName != null;
        assert cacheId != 0;

        Map<String, Integer> caches = new HashMap<>(this.caches);

        caches.put(cacheName, cacheId);

        this.caches = caches;
    }

    void onCacheStopped(String cacheName, int cacheId) {
        assert cacheName != null;
        assert cacheId != 0;

        Map<String, Integer> caches = new HashMap<>(this.caches);

        Integer rmvd = caches.remove(cacheName);

        assert rmvd != null && rmvd == cacheId : cacheName;

        this.caches = caches;
    }

    boolean hasCaches() {
        return caches != null;
    }

    public String groupName() {
        return grpName;
    }

    public int groupId() {
        return grpId;
    }

    public CacheConfiguration config() {
        return cacheCfg;
    }

    public AffinityTopologyVersion startTopologyVersion() {
        return startTopVer;
    }

    Map<String, Integer> caches() {
        return caches;
    }

    @Override public String toString() {
        return S.toString(CacheGroupDescriptor.class, this);
    }
}