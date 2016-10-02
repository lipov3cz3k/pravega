/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.emc.pravega.controller.store.stream;

import com.emc.pravega.common.concurrent.FutureCollectionHelper;
import com.emc.pravega.stream.StreamConfiguration;
import com.google.common.base.Preconditions;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Abstract Stream metadata store. It implements various read queries using the Stream interface.
 * Implementation of create and update queries are delegated to the specific implementations of this abstract class.
 */
public abstract class AbstractStreamMetadataStore implements StreamMetadataStore {

    public abstract Stream getStream(String name);

    @Override
    public CompletableFuture<Boolean> updateConfiguration(String name, StreamConfiguration configuration) {
        return getStream(name).updateConfiguration(configuration);
    }

    @Override
    public CompletableFuture<StreamConfiguration> getConfiguration(String name) {
        return getStream(name).getConfiguration();
    }

    @Override
    public CompletableFuture<Segment> getSegment(String name, int number) {
        return getStream(name).getSegment(number);
    }

    @Override
    public CompletableFuture<SegmentFutures> getActiveSegments(String name) {
        return getStream(name)
                .getActiveSegments()
                .thenApply(currentSegments -> new SegmentFutures(new ArrayList<>(currentSegments), Collections.EMPTY_MAP));
    }

    @Override
    public CompletableFuture<SegmentFutures> getActiveSegments(String name, long timestamp) {
        Stream stream = getStream(name);
        CompletableFuture<List<Integer>> futureActiveSegments = stream.getActiveSegments(timestamp);
        return futureActiveSegments.thenCompose(activeSegments -> constructSegmentFutures(stream, activeSegments));
    }

    @Override
    public CompletableFuture<List<SegmentFutures>> getNextSegments(String name, Set<Integer> completedSegments, List<SegmentFutures> positions) {
        Preconditions.checkNotNull(positions);
        Preconditions.checkArgument(positions.size() > 0);

        Stream stream = getStream(name);

        Set<Integer> current = new HashSet<>();
        positions.forEach(position ->
                position.getCurrent().forEach(current::add));

        CompletableFuture<Set<Integer>> implicitCompletedSegments =
                getImplicitCompletedSegments(stream, completedSegments, current);

        CompletableFuture<Set<Integer>> successors =
                implicitCompletedSegments.thenCompose(x -> getSuccessors(stream, x));

        CompletableFuture<List<Integer>> newCurrents =
                implicitCompletedSegments.thenCompose(x -> successors.thenCompose(y -> getNewCurrents(stream, y, x, positions)));

        CompletableFuture<Map<Integer, List<Integer>>> newFutures =
                implicitCompletedSegments.thenCompose(x -> successors.thenCompose(y -> getNewFutures(stream, y, x)));

        return newCurrents.thenCompose(x -> newFutures.thenCompose(y -> divideSegments(stream, x, y, positions)));
    }

    @Override
    public CompletableFuture<List<Segment>> scale(String name, List<Integer> sealedSegments, List<AbstractMap.SimpleEntry<Double, Double>> newRanges, long scaleTimestamp) {
        return getStream(name).scale(sealedSegments, newRanges, scaleTimestamp);
    }

    private CompletableFuture<SegmentFutures> constructSegmentFutures(Stream stream, List<Integer> activeSegments) {
        Map<Integer, Integer> futureSegments = new HashMap<>();
        List<CompletableFuture<List<Integer>>> list =
                activeSegments.stream().map(number -> getDefaultFutures(stream, number)).collect(Collectors.toList());

        CompletableFuture<List<List<Integer>>> futureDefaultFutures = FutureCollectionHelper.sequence(list);
        return futureDefaultFutures
                .thenApply(futureList -> {
                            for (int i = 0; i < futureList.size(); i++) {
                                for (Integer future : futureList.get(i)) {
                                    futureSegments.put(future, activeSegments.get(i));
                                }
                            }
                            return new SegmentFutures(new ArrayList(activeSegments), futureSegments);
                        }
                );
    }

//    private CompletableFuture<SegmentFutures> constructSegmentFutures(String name, long timestamp) {
//        Stream stream = getStream(name);
//        CompletableFuture<List<Integer>> activeSegments = stream.getActiveSegments(timestamp).thenApply(ArrayList::new);
//
//        CompletableFuture<Map<Integer, Integer>> futureSegments =
//                FutureCollectionHelper.foldFutures(activeSegments, new HashMap<>(), (x, y) -> getDefaultSegmentFutures(stream, x, y));
//
//        return activeSegments.thenCombine(futureSegments, SegmentFutures::new);
//    }
//
//    private CompletableFuture<Map<Integer, Integer>> getDefaultSegmentFutures(Stream stream, CompletableFuture<Map<Integer, Integer>> futureMap, int number) {
//        CompletableFuture<List<Integer>> futures = getDefaultFutures(stream, number);
//        return futures.thenCombine(futureMap,
//                (list, map) -> {
//                    list.stream().forEach(x -> map.put(x, number));
//                    return map;
//                });
//    }

    /**
     * Finds all successors of a given segment, that have exactly one predecessor,
     * and hence can be included in the futures of the given segment.
     * @param stream input stream
     * @param number segment number for which default futures are sought.
     * @return the list of successors of specified segment who have only one predecessor.
     *
     *         return stream.getSuccessors(number).stream()
     *               .filter(x -> stream.getPredecessors(x).size() == 1)
    .*                collect(Collectors.toList());
     */
    private CompletableFuture<List<Integer>> getDefaultFutures(Stream stream, int number) {
        CompletableFuture<List<Integer>> futureSuccessors = stream.getSuccessors(number);

        CompletableFuture<List<Integer>> futureResult =
                futureSuccessors.thenCompose(list -> {
                    List<CompletableFuture<Integer>> predecessorsSizeFuture =
                            list.stream().map(elem -> stream.getPredecessors(elem).thenApply(List::size)).collect(Collectors.toList());
                    return FutureCollectionHelper.sequence(predecessorsSizeFuture)
                            .thenApply(predecessorsSizeList -> {
                                List<Integer> result = new ArrayList<>();
                                for (int i = 0; i < predecessorsSizeList.size(); i++) {
                                    if (predecessorsSizeList.get(i) == 1) {
                                        result.add(list.get(i));
                                    }
                                }
                                return result;
                            });
                });
        return futureResult;
    }

    private CompletableFuture<Set<Integer>> getImplicitCompletedSegments(Stream stream, Set<Integer> completedSegments, Set<Integer> current) {
        List<CompletableFuture<List<Integer>>> futures = new ArrayList<>();
        current.stream().forEach(x -> futures.add(stream.getPredecessors(x)));

        // append completed segment set with implicitly completed segments
        return FutureCollectionHelper.foldFutures(
                FutureCollectionHelper.sequence(futures),
                completedSegments,
                (CompletableFuture<Set<Integer>> x, List<Integer> y) -> x.thenApply(set -> {
                    set.addAll(y);
                    return set;
                })
        );
    }

    private CompletableFuture<Set<Integer>> getSuccessors(Stream stream, Set<Integer> completedSegments) {
        // successors of completed segments are interesting, which means
        // some of them may become current, and
        // some of them may become future
        //Set<Integer> successors = completedSegments.stream().flatMap(x -> stream.getSuccessors(x).stream()).collect(Collectors.toSet());
        List<CompletableFuture<List<Integer>>> futures = new ArrayList<>();
        completedSegments.stream().forEach(x -> futures.add(stream.getSuccessors(x)));

        return FutureCollectionHelper.foldFutures(
                FutureCollectionHelper.sequence(futures),
                new HashSet<>(),
                (CompletableFuture<Set<Integer>> x, List<Integer> y) -> x.thenApply(set -> {
                    set.addAll(y);
                    return set;
                })
        );
    }

    private CompletableFuture<List<Integer>> getNewCurrents(Stream stream, Set<Integer> successors, Set<Integer> completedSegments, List<SegmentFutures> positions) {
        // a successor that has
        // 1. it is not completed yet, and
        // 2. it is not current in any of the positions, and
        // 3. all its predecessors completed.
        // shall become current and be added to some position
        List<Integer> newCurrents = successors.stream().filter(x ->
                        // 2. it is not completed yet, and
                        !completedSegments.contains(x)
                        // 3. it is not current in any of the positions
                        && positions.stream().allMatch(z -> !z.getCurrent().contains(x))
        ).collect(Collectors.toList());

        // 3. all its predecessors completed, and
        return FutureCollectionHelper.filter(
                newCurrents,
                (Integer x) -> stream.getPredecessors(x).thenApply(list -> list.stream().allMatch(completedSegments::contains))
        );
    }

    private CompletableFuture<Map<Integer, List<Integer>>> getNewFutures(Stream stream, Set<Integer> successors, Set<Integer> completedSegments) {
        List<Integer> subset = successors.stream().filter(x -> !completedSegments.contains(x)).collect(Collectors.toList());

        List<CompletableFuture<List<Integer>>> predecessors = new ArrayList<>();
        for (Integer number: subset) {
            predecessors.add(stream.getPredecessors(number)
                            .thenApply(preds -> preds.stream().filter(y -> !completedSegments.contains(y)).collect(Collectors.toList()))
            );
        }

        return FutureCollectionHelper.sequence(predecessors).thenApply((List<List<Integer>> preds) -> {
            Map<Integer, List<Integer>> map = new HashMap<>();
            for (int i = 0; i < preds.size(); i++) {
                List<Integer> filtered = preds.get(i);
                if (filtered.size() == 1) {
                    Integer pendingPredecessor = filtered.get(0);
                    if (map.containsKey(pendingPredecessor)) {
                        map.get(pendingPredecessor).add(subset.get(i));
                    } else {
                        List<Integer> list = new ArrayList<>();
                        list.add(subset.get(i));
                        map.put(pendingPredecessor, list);
                    }
                }
            }
            return map;
        });
    }

    /**
     * Divides the set of new current segments among existing positions and returns the updated positions
     * @param stream stream
     * @param newCurrents new set of current segments
     * @param newFutures new set of future segments
     * @param positions positions to be updated
     * @return the updated sequence of positions
     */
    private CompletableFuture<List<SegmentFutures>> divideSegments(Stream stream, List<Integer> newCurrents, Map<Integer, List<Integer>> newFutures, List<SegmentFutures> positions) {
        List<CompletableFuture<SegmentFutures>> newPositions = new ArrayList<>(positions.size());

        int quotient = newCurrents.size() / positions.size();
        int remainder = newCurrents.size() % positions.size();
        int counter = 0;
        for (int i = 0; i < positions.size(); i++) {
            SegmentFutures position = positions.get(i);

            // add the new current segments
            List<Integer> newCurrent = new ArrayList<>(position.getCurrent());
            int portion = (i < remainder) ? quotient + 1 : quotient;
            for (int j = 0; j < portion; j++, counter++) {
                newCurrent.add(newCurrents.get(counter));
            }
            Map<Integer, Integer> newFuture = new HashMap<>(position.getFutures());
            // add new futures if any
            position.getCurrent().forEach(
                    current -> {
                        if (newFutures.containsKey(current)) {
                            newFutures.get(current).stream().forEach(x -> newFuture.put(x, current));
                        }
                    }
            );
            // add default futures for new and old current segments, if any
            List<CompletableFuture<List<Integer>>> defaultFutures =
                    newCurrent.stream().map(x -> getDefaultFutures(stream, x)).collect(Collectors.toList());

            CompletableFuture<SegmentFutures> segmentFuture =
                    FutureCollectionHelper.sequence(defaultFutures).thenApply((List<List<Integer>> list) -> {
                        for (int k = 0; k < list.size(); k++) {
                            Integer x = newCurrent.get(k);
                            list.get(k).stream().forEach(
                                    y -> {
                                        if (!newFuture.containsKey(y)) {
                                            newFuture.put(y, x);
                                        }
                                    }
                            );
                        }
                        return new SegmentFutures(newCurrent, newFuture);
                    });
            newPositions.add(segmentFuture);
        }
        return FutureCollectionHelper.sequence(newPositions);
    }
}
