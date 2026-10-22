package com.jurislpo.juristrack.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The manager-scope rule ({@code references/INTEGRATION.md} 3b). The organisation used here:
 * <pre>
 * owner
 * ├── managerA
 * │   ├── employeeOne
 * │   ├── employeeTwo
 * │   └── leadA
 * │       ├── employeeThree
 * │       │   └── internOfThree      (three levels below managerA)
 * │       └── employeeFour
 * └── managerB
 *     └── employeeFive
 * cycleFirst ⇄ cycleSecond (each the other's manager), loner reports to himself, orphan's manager is not in the map.
 * </pre>
 */
class ReportingLineTest {

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private static final UUID OWNER = id("owner");
    private static final UUID MANAGER_A = id("managerA");
    private static final UUID MANAGER_B = id("managerB");
    private static final UUID LEAD_A = id("leadA");
    private static final UUID EMPLOYEE_ONE = id("employeeOne");
    private static final UUID EMPLOYEE_TWO = id("employeeTwo");
    private static final UUID EMPLOYEE_THREE = id("employeeThree");
    private static final UUID EMPLOYEE_FOUR = id("employeeFour");
    private static final UUID EMPLOYEE_FIVE = id("employeeFive");
    private static final UUID INTERN_OF_THREE = id("internOfThree");
    private static final UUID CYCLE_FIRST = id("cycleFirst");
    private static final UUID CYCLE_SECOND = id("cycleSecond");
    private static final UUID LONER = id("loner");
    private static final UUID ORPHAN = id("orphan");
    private static final UUID UNKNOWN = id("nobody");

    private static final ReportingLine REPORTING_LINE = new ReportingLine(organisation());

    private static Map<UUID, UUID> organisation() {
        Map<UUID, UUID> managerIdByEmployeeId = new HashMap<>();
        managerIdByEmployeeId.put(OWNER, null);
        managerIdByEmployeeId.put(MANAGER_A, OWNER);
        managerIdByEmployeeId.put(MANAGER_B, OWNER);
        managerIdByEmployeeId.put(EMPLOYEE_ONE, MANAGER_A);
        managerIdByEmployeeId.put(EMPLOYEE_TWO, MANAGER_A);
        managerIdByEmployeeId.put(LEAD_A, MANAGER_A);
        managerIdByEmployeeId.put(EMPLOYEE_THREE, LEAD_A);
        managerIdByEmployeeId.put(EMPLOYEE_FOUR, LEAD_A);
        managerIdByEmployeeId.put(INTERN_OF_THREE, EMPLOYEE_THREE);
        managerIdByEmployeeId.put(EMPLOYEE_FIVE, MANAGER_B);
        managerIdByEmployeeId.put(CYCLE_FIRST, CYCLE_SECOND);
        managerIdByEmployeeId.put(CYCLE_SECOND, CYCLE_FIRST);
        managerIdByEmployeeId.put(LONER, LONER);
        managerIdByEmployeeId.put(ORPHAN, UNKNOWN);
        return managerIdByEmployeeId;
    }

    @Test
    void depthOneIsTheDirectReportsOnly() {
        assertEquals(Set.of(EMPLOYEE_ONE, EMPLOYEE_TWO, LEAD_A), REPORTING_LINE.visibleEmployeeIds(MANAGER_A, ReportingLine.DIRECT_REPORTS));
        assertEquals(Set.of(EMPLOYEE_THREE, EMPLOYEE_FOUR), REPORTING_LINE.visibleEmployeeIds(LEAD_A, 1));
        assertEquals(Set.of(MANAGER_A, MANAGER_B), REPORTING_LINE.visibleEmployeeIds(OWNER, 1));
    }

    @Test
    void depthTwoAddsExactlyOneLevelBelow() {
        assertEquals(Set.of(EMPLOYEE_ONE, EMPLOYEE_TWO, LEAD_A, EMPLOYEE_THREE, EMPLOYEE_FOUR),
                REPORTING_LINE.visibleEmployeeIds(MANAGER_A, ReportingLine.WITH_INDIRECT_REPORTS));
        assertFalse(REPORTING_LINE.visibleEmployeeIds(MANAGER_A, 2).contains(INTERN_OF_THREE), "three levels down is out of depth 2");
        assertEquals(Set.of(MANAGER_A, MANAGER_B, EMPLOYEE_ONE, EMPLOYEE_TWO, LEAD_A, EMPLOYEE_FIVE), REPORTING_LINE.visibleEmployeeIds(OWNER, 2));
    }

    @Test
    void aDeeperDepthWidensTheScopeOneLevelAtATime() {
        assertTrue(REPORTING_LINE.visibleEmployeeIds(MANAGER_A, 3).contains(INTERN_OF_THREE));
        assertEquals(9, REPORTING_LINE.visibleEmployeeIds(OWNER, 10).size());
    }

    @Test
    void aManagerIsNeverInTheirOwnScope() {
        for (UUID manager : new UUID[] {OWNER, MANAGER_A, LEAD_A, CYCLE_FIRST, CYCLE_SECOND, LONER}) {
            for (int depth = 1; depth <= 5; depth++) {
                assertFalse(REPORTING_LINE.visibleEmployeeIds(manager, depth).contains(manager), manager + " at depth " + depth);
                assertFalse(REPORTING_LINE.canView(manager, manager, depth));
            }
        }
    }

    @Test
    void cyclesEndAndNeverIncludeTheViewer() {
        assertEquals(Set.of(CYCLE_SECOND), REPORTING_LINE.visibleEmployeeIds(CYCLE_FIRST, 1));
        assertEquals(Set.of(CYCLE_SECOND), REPORTING_LINE.visibleEmployeeIds(CYCLE_FIRST, 2));
        assertEquals(Set.of(CYCLE_FIRST), REPORTING_LINE.visibleEmployeeIds(CYCLE_SECOND, 50));
        assertEquals(Set.of(), REPORTING_LINE.visibleEmployeeIds(LONER, 3));
    }

    @Test
    void unknownAndMissingManagersAreHarmless() {
        assertEquals(Set.of(), REPORTING_LINE.visibleEmployeeIds(UUID.randomUUID(), 2));
        assertEquals(Set.of(ORPHAN), REPORTING_LINE.visibleEmployeeIds(UNKNOWN, 1), "an id that only appears as a manager still sees its reports");
        assertEquals(Set.of(), REPORTING_LINE.visibleEmployeeIds(EMPLOYEE_FIVE, 2), "an employee without reports sees nobody");
        assertFalse(REPORTING_LINE.canView(MANAGER_A, UUID.randomUUID(), 2));
        assertEquals(Set.of(), new ReportingLine(Map.of()).visibleEmployeeIds(MANAGER_A, 2));
    }

    @Test
    void nobodySeesAcrossOrUpTheChain() {
        assertFalse(REPORTING_LINE.canView(MANAGER_A, EMPLOYEE_FIVE, 2), "another manager's report");
        assertFalse(REPORTING_LINE.canView(MANAGER_B, EMPLOYEE_ONE, 2), "another manager's report");
        assertFalse(REPORTING_LINE.canView(MANAGER_A, MANAGER_B, 2), "a peer");
        assertFalse(REPORTING_LINE.canView(MANAGER_A, OWNER, 2), "their own manager");
        assertFalse(REPORTING_LINE.canView(EMPLOYEE_ONE, MANAGER_A, 2), "an employee's manager");
        assertFalse(REPORTING_LINE.canView(LEAD_A, EMPLOYEE_ONE, 2), "a sibling of the lead");
    }

    @Test
    void canViewFollowsTheDepth() {
        assertTrue(REPORTING_LINE.canView(MANAGER_A, EMPLOYEE_ONE, 1));
        assertFalse(REPORTING_LINE.canView(MANAGER_A, EMPLOYEE_THREE, 1));
        assertTrue(REPORTING_LINE.canView(MANAGER_A, EMPLOYEE_THREE, 2));
        assertFalse(REPORTING_LINE.canView(MANAGER_A, INTERN_OF_THREE, 2));
        assertTrue(REPORTING_LINE.canView(OWNER, LEAD_A, 2));
    }

    @Test
    void depthBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> REPORTING_LINE.visibleEmployeeIds(MANAGER_A, 0));
        assertThrows(IllegalArgumentException.class, () -> REPORTING_LINE.canView(MANAGER_A, EMPLOYEE_ONE, -1));
    }
}
