package com.jurislpo.juristrack.employees;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** {@code GET /api/v1/employees} and {@code GET /api/v1/employees/{id}}: the owner's view of their own business; search, filters, sorting, paging. */
class EmployeeListTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    TestData testData;
    UUID businessId;
    UUID ownerId;
    UUID managerId;
    UUID ashaId;
    UUID benId;
    UUID chenId;
    UUID otherBusinessUserId;
    Cookie ownerCookie;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("List Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("list.owner");
        ownerId = testData.createUser(businessId, "ADMIN", ownerEmail, null, "Olivia", "Owner", "L-001");
        managerId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("list.manager"), ownerId, "Manish", "Manager", "L-002");
        ashaId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("asha"), managerId, "Asha", "Rao", "L-003");
        benId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("ben"), managerId, "Ben", "Carter", null);
        chenId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("chen"), null, "Chen", "Li", "L-005");
        testData.deactivate(chenId);
        jdbcTemplate.update("UPDATE users SET last_login_at = now() - interval '1 day' WHERE id = ?", benId);
        jdbcTemplate.update("UPDATE users SET last_login_at = now() - interval '2 days' WHERE id = ?", ashaId);
        otherBusinessUserId = testData.createUser(testData.createBusiness("Other List Co " + UUID.randomUUID()), "EMPLOYEE", TestData.uniqueEmail("asha.other"),
                null, "Asha", "Elsewhere", "L-003");
        ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
    }

    @Test
    void theDefaultListShowsTheActivePeopleOfTheOwnersBusinessByName() throws Exception {
        mockMvc.perform(get("/api/v1/employees").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(25))
                .andExpect(jsonPath("$.data.rows[*].firstName").value(contains("Asha", "Ben", "Manish", "Olivia")))
                .andExpect(jsonPath("$.data.rows[*].id").value(not(hasItems(otherBusinessUserId.toString(), chenId.toString()))))
                .andExpect(jsonPath("$.data.rows[0].id").value(ashaId.toString()))
                .andExpect(jsonPath("$.data.rows[0].employeeCode").value("L-003"))
                .andExpect(jsonPath("$.data.rows[0].lastName").value("Rao"))
                .andExpect(jsonPath("$.data.rows[0].role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.rows[0].isActive").value(true))
                .andExpect(jsonPath("$.data.rows[0].managerId").value(managerId.toString()))
                .andExpect(jsonPath("$.data.rows[0].managerName").value("Manish Manager"))
                .andExpect(jsonPath("$.data.rows[0].lastLoginAt").isString())
                .andExpect(jsonPath("$.data.rows[2].activeReportCount").value(2))
                .andExpect(jsonPath("$.data.rows[3].managerName").value(nullValue()));
    }

    @Test
    void filtersNarrowTheList() throws Exception {
        mockMvc.perform(get("/api/v1/employees").param("status", "inactive").cookie(ownerCookie))
                .andExpect(jsonPath("$.data.rows[*].firstName").value(contains("Chen")));
        mockMvc.perform(get("/api/v1/employees").param("status", "all").cookie(ownerCookie)).andExpect(jsonPath("$.data.total").value(5));
        mockMvc.perform(get("/api/v1/employees").param("role", "MANAGER,ADMIN").cookie(ownerCookie))
                .andExpect(jsonPath("$.data.rows[*].firstName").value(contains("Manish", "Olivia")));
        mockMvc.perform(get("/api/v1/employees").param("role", "employee").param("status", "all").cookie(ownerCookie))
                .andExpect(jsonPath("$.data.total").value(3));
        mockMvc.perform(get("/api/v1/employees").param("managerId", managerId.toString()).cookie(ownerCookie))
                .andExpect(jsonPath("$.data.rows[*].firstName").value(contains("Asha", "Ben")));
        // search: first name, last name, full name, email, employee code; never another business
        mockMvc.perform(get("/api/v1/employees").param("search", "asha").cookie(ownerCookie))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.rows[0].id").value(ashaId.toString()));
        mockMvc.perform(get("/api/v1/employees").param("search", "ben car").cookie(ownerCookie)).andExpect(jsonPath("$.data.rows[0].id").value(benId.toString()));
        mockMvc.perform(get("/api/v1/employees").param("search", "l-00").param("status", "all").cookie(ownerCookie)).andExpect(jsonPath("$.data.total").value(4));
        mockMvc.perform(get("/api/v1/employees").param("search", "list.manager").cookie(ownerCookie)).andExpect(jsonPath("$.data.total").value(1));
        // % and _ are taken literally
        mockMvc.perform(get("/api/v1/employees").param("search", "%").cookie(ownerCookie)).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void sortingAndPaging() throws Exception {
        mockMvc.perform(get("/api/v1/employees").param("sort", "name").param("sortDirection", "desc").cookie(ownerCookie))
                .andExpect(jsonPath("$.data.rows[*].firstName").value(contains("Olivia", "Manish", "Ben", "Asha")));
        mockMvc.perform(get("/api/v1/employees").param("sort", "employeeCode").cookie(ownerCookie))
                .andExpect(jsonPath("$.data.rows[*].firstName").value(contains("Olivia", "Manish", "Asha", "Ben")));
        mockMvc.perform(get("/api/v1/employees").param("sort", "role").cookie(ownerCookie))
                .andExpect(jsonPath("$.data.rows[*].role").value(contains("ADMIN", "MANAGER", "EMPLOYEE", "EMPLOYEE")));
        mockMvc.perform(get("/api/v1/employees").param("sort", "lastLoginAt").param("sortDirection", "desc").cookie(ownerCookie))
                .andExpect(jsonPath("$.data.rows[0].firstName").value("Olivia")) // signed in just now
                .andExpect(jsonPath("$.data.rows[1].firstName").value("Ben"))
                .andExpect(jsonPath("$.data.rows[2].firstName").value("Asha"))
                .andExpect(jsonPath("$.data.rows[3].firstName").value("Manish"));
        mockMvc.perform(get("/api/v1/employees").param("sort", "status").param("status", "all").cookie(ownerCookie))
                .andExpect(jsonPath("$.data.rows[4].firstName").value("Chen"));
        mockMvc.perform(get("/api/v1/employees").param("sort", "email").cookie(ownerCookie)).andExpect(jsonPath("$.data.rows[0].firstName").value("Asha"));
        mockMvc.perform(get("/api/v1/employees").param("pageSize", "3").param("page", "2").cookie(ownerCookie))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(3))
                .andExpect(jsonPath("$.data.rows[*].firstName").value(contains("Olivia")));
        mockMvc.perform(get("/api/v1/employees").param("page", "9").cookie(ownerCookie)).andExpect(jsonPath("$.data.rows.length()").value(0));
    }

    @Test
    void unknownParametersAre400() throws Exception {
        mockMvc.perform(get("/api/v1/employees").param("status", "gone").param("role", "SUPER_ADMIN").param("managerId", "nobody")
                        .param("sort", "salary").param("page", "-1").param("pageSize", "abc").cookie(ownerCookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("status", "role", "managerId", "sort", "page", "pageSize")));
    }

    @Test
    void oneEmployeeOfTheOwnersBusiness() throws Exception {
        mockMvc.perform(get("/api/v1/employees/" + managerId).cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstName").value("Manish"))
                .andExpect(jsonPath("$.data.managerName").value("Olivia Owner"))
                .andExpect(jsonPath("$.data.activeReportCount").value(2))
                .andExpect(jsonPath("$.data.createdAt").isString());
        mockMvc.perform(get("/api/v1/employees/" + otherBusinessUserId).cookie(ownerCookie)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/employees/" + UUID.randomUUID()).cookie(ownerCookie)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/employees/123").cookie(ownerCookie)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "EMPLOYEE", "SUPER_ADMIN"})
    void onlyTheOwnerListsEmployees(String role) throws Exception {
        String email = TestData.uniqueEmail(role.toLowerCase());
        testData.createUser("SUPER_ADMIN".equals(role) ? null : businessId, role, email, null);
        Cookie cookie = SignIn.session(mockMvc, email).accessCookie();
        mockMvc.perform(get("/api/v1/employees").cookie(cookie)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/employees/" + ashaId).cookie(cookie)).andExpect(status().isForbidden());
    }

    @Test
    void signedOutIs401() throws Exception {
        mockMvc.perform(get("/api/v1/employees")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/employees/" + ashaId)).andExpect(status().isUnauthorized());
    }
}
