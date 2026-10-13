"""Correctable model-input errors, separate from storage and input integrity."""

from review_harness.domain.model import HarnessError


class SubmissionIssue(HarnessError):
    def __init__(
        self,
        reason,
        *,
        path=(),
        stage="REFERENCE",
        code="EVIDENCE_REFERENCE_INVALID",
        schema_violations=(),
    ):
        super().__init__(code, 422, "提交引用未通过校验")
        self.reason, self.path, self.stage = reason, tuple(path), stage
        self.schema_violations = tuple(schema_violations)

    def at(self, *prefix):
        return SubmissionIssue(
            self.reason,
            path=(*prefix, *self.path),
            stage=self.stage,
            code=self.code,
            schema_violations=[
                {**item, "fieldPath": [*prefix, *item["fieldPath"]]}
                for item in self.schema_violations
            ],
        )

    def diagnostic(self):
        result = {
            "reason": self.reason,
            "fieldPath": list(self.path),
            "stage": self.stage,
            "code": self.code,
        }
        if self.schema_violations:
            result["schemaViolations"] = list(self.schema_violations)
        if len(self.path) >= 2 and self.path[0] in {"evidenceCandidates", "candidates"}:
            key = (
                "evidenceCandidateIndex"
                if self.path[0] == "evidenceCandidates"
                else "candidateIndex"
            )
            result[key] = self.path[1]
        if len(self.path) >= 4 and self.path[2] in {"sourceRefs", "codeReferences"}:
            result["referenceIndex"] = self.path[3]
        return result
