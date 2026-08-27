package com.ap0stole.sheetsmith.domain.enums;

/** Where a request for a bigger ceiling has got to. */
public enum BudgetRequestStatus {

    /** Asked, and nobody has answered yet. One at a time per person. */
    PENDING,

    /**
     * Answered yes, and the limit went up. Approving without raising it is refused: the person is
     * told their limit was increased, and that has to be true.
     */
    APPROVED,

    /** Answered no. The limit is unchanged, and they are told anyway. */
    DECLINED
}
