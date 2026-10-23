package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.engine.ReportingLine;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.exception.OutOfScopeException;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.security.CurrentUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Whose data a manager or the owner may open and correct (INTEGRATION.md section 3b, decisions D15 and D16):
 * <ul>
 *   <li>ADMIN (the owner): everybody of their business except themselves.</li>
 *   <li>MANAGER: the people under them down to {@link ReportingLine#WITH_INDIRECT_REPORTS} (direct reports and their reports),
 *       whatever the Team tab's "Include indirect reports" switch shows; read from the current reporting line.</li>
 *   <li>EMPLOYEE and SUPER_ADMIN: nobody.</li>
 * </ul>
 * Nobody opens their own record here (that is the personal section) and nobody corrects their own time. Out of scope is answered
 * like unknown (404) so a caller cannot learn that a record exists.
 */
@Component
public class TeamAccessPolicy {

    private final ReportingScopeService reportingScopeService;

    public TeamAccessPolicy(ReportingScopeService reportingScopeService) {
        this.reportingScopeService = reportingScopeService;
    }

    /** True when {@code viewer} may open the data of {@code employeeId}, a user of the viewer's business; never for themselves. */
    public boolean canView(CurrentUser viewer, UUID employeeId) {
        if (viewer.userId().equals(employeeId)) return false;
        if (viewer.role() == Role.ADMIN) return true;
        if (viewer.role() == Role.MANAGER) {
            return reportingScopeService.visibleEmployeeIds(viewer.businessId(), viewer.userId(), ReportingLine.WITH_INDIRECT_REPORTS)
                    .contains(employeeId);
        }
        return false;
    }

    /**
     * Checks that {@code actor} may correct the time of {@code employeeId} (a user of the actor's business): 403
     * {@code CANNOT_CORRECT_OWN_TIME} for their own records, 404 {@code notFoundMessage} outside their scope (recorded as an out-of-scope probe).
     */
    public void requireCanCorrect(CurrentUser actor, UUID employeeId, String notFoundMessage) {
        if (actor.userId().equals(employeeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, Problems.CANNOT_CORRECT_OWN_TIME,
                    "You cannot correct your own time. Ask your manager or the owner to do it.");
        }
        if (!canView(actor, employeeId)) throw new OutOfScopeException("workday", notFoundMessage);
    }
}
