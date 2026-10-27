/*
 * Copyright (c) 2021 Works Applications Co., Ltd.
 * Modified for roma: connectNode reads roma's connection matrix.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.worksap.nlp.sudachi;

import com.worksap.nlp.sudachi.dictionary.Grammar;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;

/**
 * Sudachi's lattice on roma's connection matrix (written by :dict-tool's Slim.kt, docs/design.md "Slim dictionary"):
 * each row in pieces of 16 costs, a piece being its lowest cost plus a shared pattern. {@link #connectNode} is
 * Sudachi's own with that lookup; the rest is Sudachi's LatticeImpl. Its list of nodes per end is private, so it is
 * read once by reflection (kept by roma's R8 rules).
 */
public final class RomaLattice extends LatticeImpl {
    private final ArrayList<ArrayList<LatticeNodeImpl>> endLists;
    private final int pieces;
    private final IntBuffer index;
    private final ShortBuffer lowest;
    private final ShortBuffer patterns;

    @SuppressWarnings("unchecked")
    private RomaLattice(Grammar grammar, ByteBuffer matrix) {
        super(grammar);
        try {
            java.lang.reflect.Field f = LatticeImpl.class.getDeclaredField("endLists");
            f.setAccessible(true);
            endLists = (ArrayList<ArrayList<LatticeNodeImpl>>) f.get(this);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        int left = matrix.getInt(0);
        int right = matrix.getInt(4);
        pieces = (left + 15) / 16;
        int n = right * pieces;
        index = part(matrix, 12).asIntBuffer();
        lowest = part(matrix, 12 + n * 4).asShortBuffer();
        patterns = part(matrix, 12 + n * 6).asShortBuffer();
    }

    private static ByteBuffer part(ByteBuffer b, int at) {
        ByteBuffer d = b.duplicate();
        d.position(at);
        return d.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Gives a tokenizer of roma's dictionary the lattice that reads its matrix. */
    public static Tokenizer install(Tokenizer tokenizer, ByteBuffer matrix) {
        JapaneseTokenizer t = (JapaneseTokenizer) tokenizer;
        t.lattice = new RomaLattice(t.grammar, matrix);
        return tokenizer;
    }

    @Override
    void connectNode(LatticeNodeImpl rNode) {
        int row = rNode.leftId * pieces;
        ArrayList<LatticeNodeImpl> endNodes = endLists.get(rNode.begin);
        LatticeNodeImpl bestPrevNode = null;
        int minLeftCost = Integer.MAX_VALUE;

        // noinspection ForLoopReplaceableByForEach
        for (int i = 0; i < endNodes.size(); ++i) {
            LatticeNodeImpl lNode = endNodes.get(i);
            if (!lNode.isConnectedToBOS()) {
                continue;
            }

            int right = lNode.rightId;
            int piece = row + (right >>> 4);
            int connectCost = lowest.get(piece) + (patterns.get(index.get(piece) << 4 | (right & 15)) & 0xffff);
            if (connectCost == Grammar.INHIBITED_CONNECTION) {
                continue; // this connection is not allowed
            }
            int cost = lNode.totalCost + connectCost;
            if (cost < minLeftCost) {
                minLeftCost = cost;
                bestPrevNode = lNode;
            }
        }
        rNode.totalCost = minLeftCost + rNode.cost;
        rNode.bestPreviousNode = bestPrevNode;
    }
}
