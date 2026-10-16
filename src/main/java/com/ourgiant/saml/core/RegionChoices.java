package com.ourgiant.saml.core;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.RegionMetadata;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The regions offered in Edit Profile's Region dropdown (#243), taken from the bundled AWS SDK's
 * region metadata so the list (and names like "US East (N. Virginia)") stays current with SDK
 * upgrades. Limited to the partitions this app can sign in to: standard AWS and GovCloud.
 */
public final class RegionChoices {

    public record Choice(String id, String name) {
        @Override
        public String toString() {
            return id + " — " + name;
        }
    }

    private static final Set<String> PARTITIONS = Set.of("aws", "aws-us-gov");

    /** Standard AWS regions by id, then GovCloud. */
    public static final List<Choice> ALL = Region.regions().stream()
        .filter(region -> !region.isGlobalRegion())
        .map(region -> {
            RegionMetadata metadata = RegionMetadata.of(region);
            return metadata != null && PARTITIONS.contains(metadata.partition().id())
                ? new Choice(region.id(), metadata.description()) : null;
        })
        .filter(java.util.Objects::nonNull)
        .sorted(Comparator.comparing((Choice c) -> c.id().startsWith("us-gov-")).thenComparing(Choice::id))
        .toList();

    private RegionChoices() {
    }

    /** The listed choice for this id, or null. */
    public static Choice find(String id) {
        if (id == null) {
            return null;
        }
        for (Choice choice : ALL) {
            if (choice.id().equals(id.trim())) {
                return choice;
            }
        }
        return null;
    }
}
