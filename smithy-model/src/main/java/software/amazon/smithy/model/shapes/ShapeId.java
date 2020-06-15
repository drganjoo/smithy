/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.model.shapes;

import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import software.amazon.smithy.model.loader.ParserUtils;
import software.amazon.smithy.model.loader.Prelude;

/**
 * Immutable identifier for each shape in a model.
 *
 * <p>A shape ID is constructed from an absolute or relative shape
 * reference. A shape reference has the following structure:
 *
 * {@code NAMESPACE#NAME$MEMBER}
 *
 * <p>An absolute reference contains a namespace and a pound sign.
 * A relative reference omits the namespace and pound sign prefix. In
 * both absolute and relative shape references, the member is optional.
 *
 * <ul>
 *   <li>Relative path : {@code ShapeName}</li>
 *   <li>Relative path with a member : {@code ShapeName$memberName}</li>
 *   <li>Absolute path : {@code name.space#ShapeName}</li>
 *   <li>Absolute path with a member : {@code name.space#ShapeName$memberName}</li>
 * </ul>
 */
public final class ShapeId implements ToShapeId, Comparable<ShapeId> {

    /** LRA (least recently added) cache of parsed shape IDs. */
    private static final ShapeIdFactory FACTORY = new ShapeIdFactory(1024);

    private final String namespace;
    private final String name;
    private final String member;
    private final String absoluteName;
    private int hash;

    private ShapeId(String absoluteName, String namespace, String name, String member) {
        this.namespace = namespace;
        this.name = name;
        this.member = member;
        this.absoluteName = absoluteName;
    }

    private ShapeId(String namespace, String name, String member) {
        this(buildAbsoluteIdFromParts(namespace, name, member), namespace, name, member);
    }

    /**
     * Creates an absolute shape ID from the given string.
     *
     * @param id Shape ID to parse.
     * @return The parsed ID.
     * @throws ShapeIdSyntaxException when the ID is malformed.
     */
    public static ShapeId from(String id) {
        return FACTORY.create(id);
    }

    /**
     * Checks if the given string is a valid namespace.
     *
     * @param namespace Namespace value to check.
     * @return Returns true if this is a valid namespace.
     */
    public static boolean isValidNamespace(CharSequence namespace) {
        if (namespace == null) {
            return false;
        }

        // Shortcut for prelude namespaces.
        if (namespace.equals(Prelude.NAMESPACE)) {
            return true;
        }

        boolean start = true;
        for (int position = 0; position < namespace.length(); position++) {
            char c = namespace.charAt(position);
            if (start) {
                start = false;
                if (!ParserUtils.isIdentifierStart(c)) {
                    return false;
                }
            } else if (c == '.') {
                start = true;
            } else if (!ParserUtils.isValidIdentifierCharacter(c)) {
                return false;
            }
        }

        return !start;
    }

    /**
     * Checks if the given string is a valid identifier.
     *
     * @param identifier Identifier value to check.
     * @return Returns true if this is a valid identifier.
     */
    public static boolean isValidIdentifier(CharSequence identifier) {
        if (identifier == null || identifier.length() == 0) {
            return false;
        }

        if (!ParserUtils.isIdentifierStart(identifier.charAt(0))) {
            return false;
        }

        for (int i = 1; i < identifier.length(); i++) {
            if (!ParserUtils.isValidIdentifierCharacter(identifier.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    /**
     * Creates an absolute shape ID from parts of a shape ID.
     *
     * @param namespace Namespace of the shape.
     * @param name Name of the shape.
     * @param member Optional/nullable member name.
     * @return The parsed ID.
     * @throws ShapeIdSyntaxException when the ID is malformed.
     */
    public static ShapeId fromParts(String namespace, String name, String member) {
        String idFromParts = buildAbsoluteIdFromParts(namespace, name, member);
        validateParts(idFromParts, namespace, name, member);
        return new ShapeId(idFromParts, namespace, name, member);
    }

    private static void validateParts(String absoluteId, String namespace, String name, String member) {
        if (!isValidNamespace(namespace)
                || !isValidIdentifier(name)
                || (member != null && !isValidIdentifier(member))) {
            throw new ShapeIdSyntaxException("Invalid shape ID: " + absoluteId);
        }
    }

    private static String buildAbsoluteIdFromParts(String namespace, String name, String member) {
        if (member != null) {
            return namespace + '#' + name + '$' + member;
        } else {
            return namespace + '#' + name;
        }
    }

    /**
     * Creates an absolute shape ID from parts of a shape ID.
     *
     * @param namespace Namespace of the shape.
     * @param name Name of the shape.
     * @return The parsed ID.
     * @throws ShapeIdSyntaxException when the ID is malformed.
     */
    public static ShapeId fromParts(String namespace, String name) {
        return fromParts(namespace, name, null);
    }

    /**
     * Builds a {@code Id} from a relative shape reference.
     *
     * <p>The given shape reference must not contain a namespace prefix.
     * It may contain a member.
     *
     * @param namespace The namespace.
     * @param relativeName A relative shape reference.
     * @return Returns a {@code Id} extracted from {@code relativeName}.
     * @throws ShapeIdSyntaxException when the namespace or shape reference
     * is malformed.
     */
    public static ShapeId fromRelative(String namespace, String relativeName) {
        Objects.requireNonNull(namespace, "Shape ID namespace must not be null");
        Objects.requireNonNull(relativeName, "Shape ID relative name must not be null");
        if (relativeName.contains("#")) {
            throw new ShapeIdSyntaxException("Relative shape ID must not contain a namespace: " + relativeName);
        }
        return from(namespace + "#" + relativeName);
    }

    /**
     * Builds a {@code Id} from the given reference.
     *
     * <p>If the shape reference contains a namespace, it is treated as an
     * absolute reference. If it does not contain a namespace prefix, it is
     * treated as a relative shape reference and the given default namespace
     * is used.
     *
     * @param defaultNamespace The namespace to use when the shape reference
     * does not contain a namespace.
     * @param shapeName A relative or absolute shape reference.
     * @return Returns a {@code Id} extracted from shape reference.
     * @throws ShapeIdSyntaxException when the namespace or shape reference
     * is malformed.
     */
    public static ShapeId fromOptionalNamespace(String defaultNamespace, String shapeName) {
        Objects.requireNonNull(shapeName, "Shape name must not be null");
        if (defaultNamespace == null || shapeName.contains("#")) {
            return ShapeId.from(shapeName);
        } else {
            return fromRelative(defaultNamespace, shapeName);
        }
    }

    /**
     * Creates a new Shape.Id with a member add to it.
     *
     * @param member Member to set.
     * @return returns a new Shape.Id
     * @throws ShapeIdSyntaxException if the member name syntax is invalid.
     */
    public ShapeId withMember(String member) {
        if (!isValidIdentifier(member)) {
            throw new ShapeIdSyntaxException("Invalid shape ID member: " + member);
        }

        return new ShapeId(namespace, name, member);
    }

    @Override
    public ShapeId toShapeId() {
        return this;
    }

    @Override
    public int compareTo(ShapeId other) {
        int outcome = toString().compareToIgnoreCase(other.toString());
        if (outcome == 0) {
            // If they're case-insensitively equal, use a case-sensitive comparison as a tie-breaker.
            return toString().compareTo(other.toString());
        }
        return outcome;
    }

    /**
     * Creates a new Shape.Id with no member.
     *
     * @return returns a new Shape.Id
     */
    public ShapeId withoutMember() {
        return new ShapeId(namespace, name, null);
    }

    /**
     * Get the namespace of the shape.
     *
     * @return Returns the namespace.
     */
    public String getNamespace() {
        return namespace;
    }

    /**
     * Get the name of the shape.
     *
     * @return Returns the name.
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the optional member of the shape.
     *
     * @return Returns the optional member.
     */
    public Optional<String> getMember() {
        return Optional.ofNullable(member);
    }

    /**
     * Creates a string that contains a relative reference to the ID.
     *
     * @return Returns a relative shape ID string with no namespace.
     */
    public String asRelativeReference() {
        return member == null ? name : name + "$" + member;
    }

    /**
     * Creates a shape ID that uses a different namespace than the current ID.
     *
     * @param namespace Namespace to use.
     * @return Returns the shape ID with the changed namespace.
     * @throws ShapeIdSyntaxException if the namespace is invalid.
     */
    public ShapeId withNamespace(String namespace) {
        if (this.namespace.equals(namespace)) {
            return this;
        } else if (!isValidNamespace(namespace)) {
            throw new ShapeIdSyntaxException("Invalid shape ID: " + namespace);
        } else {
            return new ShapeId(namespace, name, member);
        }
    }

    /**
     * Converts the {@code Id} into a shape ID string.
     *
     * For example: "com.foo.bar#Baz$member".
     *
     * @return Returns a shape ID as a string.
     */
    @Override
    public String toString() {
        return absoluteName;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ShapeId && other.toString().equals(this.toString());
    }

    @Override
    public int hashCode() {
        int h = hash;

        if (h == 0) {
            h = 17 + 31 * namespace.hashCode() * 31 + name.hashCode() * 17 + Objects.hashCode(member);
            hash = h;
        }

        return h;
    }

    /**
     * A least-recently used flyweight factory that creates shape IDs.
     *
     * <p>Shape IDs are cached in a ConcurrentHashMap. Entries are stored in
     * one of two ConcurrentLinkedQueues: a prioritized queue stores all
     * shapes IDs in the 'smithy.api' namespace, while a deprioritized queue
     * stores all shapes IDs in other namespaces. When the *estimated*
     * number of cached items exceeds {@code maxSize}, entries are removed
     * from the cache in the order of least recently added (not least
     * recently used) until the number of estimated entries is less than
     * {@code maxSize}. A simple LRU cache implementation that uses a
     * ConcurrentLinkedQueue would be much more expensive since it requires
     * that cache hits remove and then re-add a key to the queue (in micro
     * benchmarks, removing and adding to a ConcurrentLinkedQueue on a
     * cache hit was shown to be a fairly significant performance penalty).
     *
     * <p>When evicting, entries are removed by first draining the
     * deprioritized queue, and then, if the deprioritized queue is empty,
     * draining the prioritized queue. Draining the prioritized queue should
     * only be necessary to account for misbehaving inputs.
     *
     * <p>The number of entries in the cache is an estimate because the result
     * of calling {@link ConcurrentHashMap#size()} is an estimate. This is
     * acceptable for a cache though, and any time the number of entries
     * exceeds {@code maxSize}, the cache will likely get truncated on
     * subsequent cache-misses.
     *
     * <p>While not an optimal cache, this cache is the way it is because it's
     * meant to be concurrent, non-blocking, lightweight, dependency-free, and
     * should improve the performance of normal workflows of using Smithy.
     *
     * <p>Other alternatives considered were:
     *
     * <ol>
     *     <li>Pull in Caffeine. While appealing since you basically can't do
     *     better in terms of performance, it is a dependency that we wouldn't
     *     otherwise need to take, and we've drawn a pretty hard line in
     *     Smithy to be dependency-free. We could potentially "vendor" parts
     *     of Caffeine, but it's a large library that doesn't lend itself well
     *     towards that use case.</li>
     *     <li>Just use an unbounded ConcurrentHashMap. While simple, this
     *     approach is not good for long running processes where you can't
     *     control the input</li>
     *     <li>Use an {@code AtomicInteger} to cap the maximum size of a
     *     ConcurrentHashMap, and when the maximum size is hit, stop caching
     *     entries. This approach would work for most normal use cases but
     *     would not work well for long running processes that potentially
     *     load multiple models.</li>
     * </ol>
     */
    private static final class ShapeIdFactory {
        private final int maxSize;
        private final Queue<String> priorityQueue = new ConcurrentLinkedQueue<>();
        private final Queue<String> deprioritizedQueue = new ConcurrentLinkedQueue<>();
        private final ConcurrentMap<String, ShapeId> map;

        ShapeIdFactory(final int maxSize) {
            this.maxSize = maxSize;
            map = new ConcurrentHashMap<>(maxSize);
        }

        ShapeId create(final String key) {
            ShapeId value = map.get(key);

            // Return the value if it exists in the cache.
            if (value != null) {
                return value;
            }

            value = buildShapeId(key);

            // Only update the queue if the value is new to the map.
            if (map.put(key, value) == null) {
                offer(key, value);
                evict();
            }

            return value;
        }

        private ShapeId buildShapeId(String absoluteShapeId) {
            int namespacePosition = absoluteShapeId.indexOf('#');
            if (namespacePosition <= 0 || namespacePosition == absoluteShapeId.length() - 1) {
                throw new ShapeIdSyntaxException("Invalid shape ID: " + absoluteShapeId);
            }

            String namespace = absoluteShapeId.substring(0, namespacePosition);
            String name;
            String memberName = null;

            int memberPosition = absoluteShapeId.indexOf('$');
            if (memberPosition == -1) {
                name = absoluteShapeId.substring(namespacePosition + 1);
            } else if (memberPosition < namespacePosition) {
                throw new ShapeIdSyntaxException("Invalid shape ID: " + absoluteShapeId);
            } else {
                name = absoluteShapeId.substring(namespacePosition + 1, memberPosition);
                memberName = absoluteShapeId.substring(memberPosition + 1);
            }

            validateParts(absoluteShapeId, namespace, name, memberName);
            return new ShapeId(absoluteShapeId, namespace, name, memberName);
        }

        // Enqueue a key into the appropriate queue, putting more often used
        // IDs in the smithy.api# namespace into a priority queue.
        private void offer(String key, ShapeId value) {
            if (value.getNamespace().equals(Prelude.NAMESPACE)) {
                priorityQueue.offer(key);
            } else {
                deprioritizedQueue.offer(key);
            }
        }

        // Purge least recently added items from the cache.
        private void evict() {
            // Note: the result of map.size is an estimate, but that's ok for a cache.
            while (map.size() > maxSize) {
                String item = poll();
                // Stop trying to remove items from the queue if neither
                // the priority queue or deprioritized queues have entries,
                // or if attempting to remove an item from the map fails.
                if (item == null || map.remove(item) == null) {
                    break;
                }
            }
        }

        // Remove an element from the deprioritized queue if it isn't empty,
        // and if it is, then try to remove an element from the priority queue.
        private String poll() {
            String item = deprioritizedQueue.poll();
            return item != null ? item : priorityQueue.poll();
        }
    }
}
