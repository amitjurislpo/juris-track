package com.jurislpo.juristrack.controller;

import com.jurislpo.juristrack.common.ApiResult;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.security.Auth;
import com.jurislpo.juristrack.service.TeamService;
import com.jurislpo.juristrack.service.TeamService.TeamBoard;
import com.jurislpo.juristrack.service.TeamService.TeamMemberDetail;
import com.jurislpo.juristrack.service.WorkdayHistoryService.WorkdayHistory;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/team/members}: the manager's Team tab (live board, one person's state, one person's history). MANAGER and the owner
 * (ADMIN); everybody else 403. Rules: {@link TeamService}; contract: references/API-team-and-corrections.md.
 */
@RestController
@RequestMapping("/api/team/members")
public class TeamController {

    private static final Role[] TEAM_ROLES = {Role.ADMIN, Role.MANAGER};

    private final Auth auth;
    private final TeamService teamService;

    public TeamController(Auth auth, TeamService teamService) {
        this.auth = auth;
        this.teamService = teamService;
    }

    @GetMapping
    public ApiResult<TeamBoard> listTeamMembers(@RequestParam Map<String, String> queryParameters) {
        return ApiResult.success(teamService.listMembers(auth.requireRole(TEAM_ROLES), queryParameters));
    }

    @GetMapping("/{employeeId}")
    public ApiResult<TeamMemberDetail> findTeamMember(@PathVariable UUID employeeId) {
        return ApiResult.success(teamService.findMember(auth.requireRole(TEAM_ROLES), employeeId));
    }

    @GetMapping("/{employeeId}/workdays")
    public ApiResult<WorkdayHistory> listTeamMemberWorkdays(@PathVariable UUID employeeId, @RequestParam Map<String, String> queryParameters) {
        return ApiResult.success(teamService.listMemberWorkdays(auth.requireRole(TEAM_ROLES), employeeId, queryParameters));
    }
}
