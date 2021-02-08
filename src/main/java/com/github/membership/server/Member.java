package com.github.membership.server;

public final class Member {
    private String memberId;
    private String cohortId;
    private CohortType cohortType;

    public String getMemberId() {
        return memberId;
    }

    public void setMemberId(String memberId) {
        this.memberId = memberId;
    }

    public String getCohortId() {
        return cohortId;
    }

    public void setCohortId(String cohortId) {
        this.cohortId = cohortId;
    }

    public CohortType getCohortType() {
        return cohortType;
    }

    public void setCohortType(CohortType cohortType) {
        this.cohortType = cohortType;
    }

    @Override
    public String toString() {
        return "Member [memberId=" + memberId + ", cohortId=" + cohortId + ", cohortType=" + cohortType + "]";
    }

}