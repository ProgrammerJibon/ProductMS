package com.productms.util;

import java.util.List;

/**
 * Thrown when an action breaks a limit that a manager may override (sale amount, discount,
 * minimum price, price override, credit limit, refund amount...). The UI asks for manager
 * credentials and retries with the approver's name.
 */
public class ApprovalRequiredException extends AppException {

    public final List<String> reasons;

    public ApprovalRequiredException(List<String> reasons) {
        super("Manager approval needed:\n• " + String.join("\n• ", reasons));
        this.reasons = reasons;
    }
}
