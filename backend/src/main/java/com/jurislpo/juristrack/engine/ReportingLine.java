package com.jurislpo.juristrack.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who a manager may see: the people under them in the reporting line. Every user has at most one manager ({@code users.manager_id});
 * the reporting line replaces the old app's teams, so this rule is new and has no equivalent in the old code
 * ({@code references/INTEGRATION.md}, section 3b).
 *
 * <ul>
 *   <li>Depth 1 ({@link #DIRECT_REPORTS}, the default): the people whose manager is the viewer.</li>
 *   <li>Depth 2 ({@link #WITH_INDIRECT_REPORTS}, the "Include indirect reports" switch): also the direct reports of those people.
 *       Each further depth adds one more level, so the scope can be widened later without a redesign.</li>
 *   <li>A manager is <b>never</b> in their own scope, even when the data contains a cycle (A reports to B, B reports to A) or a
 *       self-reference, so a manager can never see or correct their own time through it. People above or beside the manager are
 *       never included.</li>
 *   <li>Unknown ids are harmless: an unknown manager sees nobody, and an employee whose manager is missing (not in the map, or
 *       {@code null}) simply has no manager.</li>
 * </ul>
 *
 * The map is read once; build a new instance when the reporting line changes. Spring-free and thread-safe after construction.
 */
public final class ReportingLine {

    /** Depth that shows only the direct reports. */
    public static final int DIRECT_REPORTS = 1;
    /** Depth that also shows the reports of the direct reports (one level further down). */
    public static final int WITH_INDIRECT_REPORTS = 2;

    private final Map<UUID, List<UUID>> directReportsByManagerId;

    /** @param managerIdByEmployeeId every employee's manager id ({@code null} or absent: no manager). */
    public ReportingLine(Map<UUID, UUID> managerIdByEmployeeId) {
        Map<UUID, List<UUID>> directReports = new HashMap<>();
        managerIdByEmployeeId.forEach((employeeId, managerId) -> {
            if (employeeId != null && managerId != null) {
                directReports.computeIfAbsent(managerId, newManagerId -> new ArrayList<>()).add(employeeId);
            }
        });
        directReports.replaceAll((managerId, reports) -> List.copyOf(reports));
        this.directReportsByManagerId = Map.copyOf(directReports);
    }

    /**
     * The ids of the employees the manager may see at the given depth (1 = direct reports, 2 = also one level below), never the
     * manager themselves. Empty for an unknown manager or one without reports.
     *
     * @throws IllegalArgumentException when {@code depth} is below 1
     */
    public Set<UUID> visibleEmployeeIds(UUID managerId, int depth) {
        if (depth < DIRECT_REPORTS) {
            throw new IllegalArgumentException("The reporting depth must be at least 1, was " + depth);
        }
        Set<UUID> visible = new LinkedHashSet<>();
        List<UUID> currentLevel = List.of(managerId);
        for (int level = 1; level <= depth && !currentLevel.isEmpty(); level++) {
            List<UUID> nextLevel = new ArrayList<>();
            for (UUID levelManagerId : currentLevel) {
                for (UUID reportId : directReportsByManagerId.getOrDefault(levelManagerId, List.of())) {
                    if (!reportId.equals(managerId) && visible.add(reportId)) {
                        nextLevel.add(reportId);
                    }
                }
            }
            currentLevel = nextLevel;
        }
        return Collections.unmodifiableSet(visible);
    }

    /** True when {@code employeeId} is in the viewer's scope at the given depth; always false for the viewer's own id. */
    public boolean canView(UUID viewerId, UUID employeeId, int depth) {
        return !viewerId.equals(employeeId) && visibleEmployeeIds(viewerId, depth).contains(employeeId);
    }
}
