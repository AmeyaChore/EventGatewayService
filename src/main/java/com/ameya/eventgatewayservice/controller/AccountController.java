package com.ameya.eventgatewayservice.controller;

import com.ameya.eventgatewayservice.service.accountapi.AccountServiceClient;
import com.ameya.eventgatewayservice.service.accountapi.models.AccountDetailsResponse;
import com.ameya.eventgatewayservice.service.accountapi.models.BalanceResponse;
import io.micrometer.tracing.Tracer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * NOTE: this is NOT part of the original take-home spec (the Gateway's
 * only required public endpoints are under /events). These are
 * additional, deliberately added endpoints that proxy account lookups
 * through to the Account Service - useful for a client that only talks to
 * the public-facing Gateway and should never call the internal-only
 * Account Service directly.
 */
@RestController
@RequestMapping("/events/accounts")
public class AccountController {

    private final AccountServiceClient accountService;
    private final Tracer tracer;

    public AccountController(AccountServiceClient accountService, Tracer tracer) {
        this.accountService = accountService;
        this.tracer = tracer;
    }

    @GetMapping("/{accountId}/balance")
    @Operation(summary = "Get an account's current balance",
            description = "Proxies to the Account Service. Balance is always computed fresh (sum of CREDITs minus sum of DEBITs).")
    @ApiResponse(responseCode = "200", description = "Balance returned")
    @ApiResponse(responseCode = "404", description = "Account does not exist")
    @ApiResponse(responseCode = "503", description = "Account Service is unreachable")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable(required = true) @NotBlank String accountId) {


        BalanceResponse balance = accountService.getBalance(accountId);
        return ResponseEntity.ok(balance);
        // Any failure (account not found, Account Service unavailable, etc.)
        // throws one of the Gateway's own exceptions from
        // AccountServiceClientImpl's fallback methods, which
        // GlobalExceptionHandler already maps to the correct status -
        // no try/catch needed here.
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get account details and recent transactions",
            description = "Proxies to the Account Service. Returns account metadata, current balance, and the 10 most recent transactions.")
    @ApiResponse(responseCode = "200", description = "Account details returned")
    @ApiResponse(responseCode = "404", description = "Account does not exist")
    @ApiResponse(responseCode = "503", description = "Account Service is unreachable")
    public ResponseEntity<AccountDetailsResponse> getAccount(@PathVariable(required = true) @NotBlank String accountId) {
        AccountDetailsResponse details = accountService.getAccount(accountId);
        return ResponseEntity.ok(details);
        // Same as getBalance: failures are already translated into the
        // right Gateway exception by AccountServiceClientImpl, and
        // GlobalExceptionHandler maps those to the correct HTTP status.
    }

}