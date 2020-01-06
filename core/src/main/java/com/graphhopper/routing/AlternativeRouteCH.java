/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.graphhopper.routing;

import com.carrotsearch.hppc.IntIndexedContainer;
import com.carrotsearch.hppc.predicates.IntObjectPredicate;
import com.graphhopper.routing.ch.CHWeighting;
import com.graphhopper.routing.ch.NodeBasedCHBidirPathExtractor;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.SPTEntry;
import com.graphhopper.util.EdgeIteratorState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Minimum number-of-moving-parts implementation of alternative route search with
 * contraction hierarchies.
 * <p>
 * "Alternative Routes in Road Networks" (Abraham et al.)
 *
 * @author michaz
 */
public class AlternativeRouteCH extends DijkstraBidirectionCHNoSOD {

    private double maxWeightFactor = 1.4;
    private double maxShareFactor = 0.7;
    public static final double T = 10000.0;

    public AlternativeRouteCH(Graph graph, Weighting weighting) {
        super(graph, weighting);
    }

    List<AlternativeInfo> calcAlternatives(final int s, final int t) {
        // First, do a regular bidirectional route search
        checkAlreadyRun();
        init(s, 0, t, 0);
        runAlgo();
        final Path bestPath = extractPath();
        if (!bestPath.isFound()) {
            return Collections.emptyList();
        }

        final List<AlternativeInfo> alternatives = new ArrayList<>();
        alternatives.add(new AlternativeInfo(bestPath, 0));
        final double maxWeight = maxWeightFactor * bestPath.getWeight();

        bestWeightMapFrom.forEach(new IntObjectPredicate<SPTEntry>() {
            @Override
            public boolean apply(final int v, final SPTEntry fromSPTEntry) {
                SPTEntry toSPTEntry = bestWeightMapTo.get(v);
                if (toSPTEntry == null)
                    return true;

                // Filter alternatives that are too long
                if (fromSPTEntry.getWeightOfVisitedPath() + toSPTEntry.getWeightOfVisitedPath() > maxWeight)
                    return true;

                // This gives us a path s -> v -> t, but since we are using contraction hierarchies,
                // s -> v and v -> t need not be shortest paths (see ).
                // In fact, they can sometimes be pretty strange. We still use this preliminary path to filter
                // for shared path length with other alternatives, so we don't have to work so much.
//                Path preliminaryRoute = createPathExtractor(graph, weighting).extract(fromSPTEntry, toSPTEntry, fromSPTEntry.getWeightOfVisitedPath() + toSPTEntry.getWeightOfVisitedPath());
//                double preliminaryShare = calculateShare(preliminaryRoute);
//                if (preliminaryShare > maxShareFactor) {
//                    return true;
//                }
                // this reduces alternative rate from 2.0 to 1.6 but makes algorithm roughly 3x faster
                final List<SPTEntry> sptEntries = calcSPEList(fromSPTEntry, toSPTEntry);
                double preliminaryShare = calculateShare(sptEntries) / (fromSPTEntry.getWeightOfVisitedPath() + toSPTEntry.getWeightOfVisitedPath());
                if (preliminaryShare > maxShareFactor) {
                    return true;
                }

                // Okay, now we want the s -> v -> t shortest via-path, so we route s -> v and v -> t
                // and glue them together.
                DijkstraBidirectionCHNoSOD svRouter = new DijkstraBidirectionCHNoSOD(graph, weighting);
                svRouter.setEdgeFilter(additionalEdgeFilter);
                final Path svPath = svRouter.calcPath(s, v);
                final IntIndexedContainer svNodes = svPath.calcNodes();
                DijkstraBidirectionCHNoSOD vtRouter = new DijkstraBidirectionCHNoSOD(graph, weighting);
                vtRouter.setEdgeFilter(additionalEdgeFilter);
                final Path vtPath = vtRouter.calcPath(v, t);
                final IntIndexedContainer vtNodes = vtPath.calcNodes();
                // TODO NOW remove creating anonymous class and call methods directly
                Path path = new Path(graph.getBaseGraph()) {
                    Path extract() {
                        setFromNode(svNodes.get(0));
                        for (EdgeIteratorState edge : svPath.calcEdges()) {
                            addEdge(edge.getEdge());
                        }
                        for (EdgeIteratorState edge : vtPath.calcEdges()) {
                            addEdge(edge.getEdge());
                        }
                        setEndNode(vtNodes.get(vtNodes.size() - 1));
                        setFound(true);
                        setWeight(svPath.getWeight() + vtPath.getWeight());
                        setDistance(svPath.getDistance() + vtPath.getDistance());
                        time = svPath.time + vtPath.time;
                        return this;
                    }
                }.extract();

                // And calculate the share again, because this can be totally different.
                // The first filter is a good heuristic, but we still need this one.
                double share = calculateShare(path);
                if (share > maxShareFactor) {
                    return true;
                }

                // This is the final test we need: Discard paths that are not "locally shortest" around v.
                // So move a couple of nodes to the left and right from v on our path,
                // route, and check if v is on the shortest path.
                int vIndex = svNodes.size() - 1;
                if (!tTest(path, vIndex))
                    return true;

                alternatives.add(new AlternativeInfo(path, share));
                return true;
            }

            private double calculateShare(final List<SPTEntry> sptEntries) {
                double sharedWeight = 0.0;
                for (int i = 0; i < sptEntries.size(); i++) {
                    SPTEntry entry = sptEntries.get(i);
                    if (nodesInCurrentAlternativeSetContains(entry.adjNode)) {
                        sharedWeight += entry.weight;
                    }
                }
                return sharedWeight;
            }

            // TODO NOW unit should be consistent and as we compare to maxShareFactor the "share" should be about weight not distance
            // TODO NOW currently we do contains check 2*nodes and we can reduce this to 1*nodes
            private double calculateShare(final Path path) {
                double sharedDistance = 0.0;
                List<EdgeIteratorState> edges = path.calcEdges();
                for (EdgeIteratorState edge : edges) {
                    if (nodesInCurrentAlternativeSetContains(edge.getBaseNode()) && nodesInCurrentAlternativeSetContains(edge.getAdjNode())) {
                        sharedDistance += edge.getDistance();
                    }
                }
                return sharedDistance / path.getDistance();
            }

            private boolean nodesInCurrentAlternativeSetContains(int v) {
                for (AlternativeInfo alternative : alternatives) {
                    if (alternative.nodes.contains(v)) {
                        return true;
                    }
                }
                return false;
            }

            private boolean tTest(Path path, int vIndex) {
                if (path.getEdgeCount() == 0) return true;
                int fromNode = getPreviousNodeTMetersAway(path, vIndex);
                int toNode = getNextNodeTMetersAway(path, vIndex);
                DijkstraBidirectionCHNoSOD tRouter = new DijkstraBidirectionCHNoSOD(graph, new CHWeighting(weighting));
                tRouter.setEdgeFilter(additionalEdgeFilter);
                Path tPath = tRouter.calcPath(fromNode, toNode);
                IntIndexedContainer tNodes = tPath.calcNodes();
                return tNodes.contains(path.calcNodes().get(vIndex));
            }

            private int getPreviousNodeTMetersAway(Path path, int vIndex) {
                List<EdgeIteratorState> edges = path.calcEdges();
                double distance = 0.0;
                int i = vIndex;
                while (i > 0 && distance < T) {
                    distance += edges.get(i - 1).getDistance();
                    i--;
                }
                return edges.get(i).getBaseNode();
            }

            private int getNextNodeTMetersAway(Path path, int vIndex) {
                List<EdgeIteratorState> edges = path.calcEdges();
                double distance = 0.0;
                int i = vIndex;
                while (i < edges.size() - 1 && distance < T) {
                    distance += edges.get(i).getDistance();
                    i++;
                }
                return edges.get(i - 1).getAdjNode();
            }

        });
        Collections.sort(alternatives, new Comparator<AlternativeInfo>() {
            @Override
            public int compare(AlternativeInfo o1, AlternativeInfo o2) {
                return Double.compare(o1.path.getWeight(), o2.path.getWeight());
            }
        });
        return alternatives;
    }

    private List<SPTEntry> calcSPEList(final SPTEntry fromSPTEntry, final SPTEntry toSPTEntry) {
        final List<SPTEntry> list = new ArrayList<>();
        new NodeBasedCHBidirPathExtractor(graph, graph.getBaseGraph(), weighting) {
            @Override
            public void onEdge(SPTEntry entry, int edge, int adjNode, boolean reverse, int prevOrNextEdge) {
                list.add(entry);
            }
        }.extract(fromSPTEntry, toSPTEntry, -1);
        return list;
    }

    @Override
    public List<Path> calcPaths(int from, int to) {
        List<AlternativeInfo> alts = calcAlternatives(from, to);
        if (alts.isEmpty()) {
            return Collections.singletonList(createEmptyPath());
        }
        List<Path> paths = new ArrayList<>(alts.size());
        for (AlternativeInfo a : alts) {
            paths.add(a.path);
        }
        return paths;
    }

    public void setMaxWeightFactor(double maxWeightFactor) {
        this.maxWeightFactor = maxWeightFactor;
    }

    public void setMaxShareFactor(double maxShareFactor) {
        this.maxShareFactor = maxShareFactor;
    }

    public static class AlternativeInfo {
        final Path path;
        final double shareWeight;
        final IntIndexedContainer nodes;

        AlternativeInfo(Path path, double shareWeight) {
            this.path = path;
            this.shareWeight = shareWeight;
            this.nodes = path.calcNodes();
        }

        public Path getPath() {
            return path;
        }

        @Override
        public String toString() {
            return "AlternativeInfo{" +
                    "path=" + path +
                    ", shareWeight=" + shareWeight +
                    ", nodes=" + nodes +
                    '}';
        }
    }

    public class Searcher extends DijkstraBidirectionCHNoSOD {

        public Searcher(Graph graph, Weighting weighting) {
            super(graph, weighting);
            setEdgeFilter(additionalEdgeFilter);
        }

        public List<SPTEntry> calcSPTEntries(int from, int to) {
            checkAlreadyRun();
            init(from, 0, to, 0);
            runAlgo();
            if (finished())
                return calcSPEList(currFrom, currTo);

            return Collections.emptyList();
        }
    }

}
