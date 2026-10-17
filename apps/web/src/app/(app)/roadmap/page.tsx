import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { PageHeader } from "@/components/ui/page-header";

// Static description of planned modules. Nothing here is presented as working.
const milestones = [
  { id: "M1", title: "Platform foundation", status: "Available", items: ["Organizations, roles and API tokens", "Secure source snapshot ingestion", "Isolated test execution with evidence", "Job queue with leasing, retries and cancellation"] },
  { id: "M2", title: "Code intelligence", status: "Planned", items: ["TypeScript language adapter", "Symbol and dependency indexing", "Rule-based static analysis", "Findings explorer and Monaco source viewer"] },
  { id: "M3", title: "Break My Code", status: "Planned", items: ["Property-based test generation", "Fuzzing with input shrinking", "Mutation testing", "Reproducible defect records"] },
  { id: "M4", title: "Repair verification", status: "Planned", items: ["Patch ingestion", "Baseline versus candidate comparison", "Regression detection", "CI quality gates"] },
  { id: "M5", title: "Multi-language engineering", status: "Planned", items: ["Kotlin analysis", "OCaml analysis components", "Fortran numerical verification", "COBOL legacy laboratory"] },
  { id: "M6", title: "Advanced intelligence", status: "Planned", items: ["AI-assisted explanations and repair proposals", "Formal verification", "WebGL architecture graph"] },
  { id: "M7", title: "Commercial platform", status: "Planned", items: ["Subscriptions and metering", "Enterprise SSO", "Private deployment tooling"] },
];

export default function RoadmapPage() {
  return (
    <>
      <PageHeader title="Roadmap" description="Modules are listed here until their backend exists. Planned modules have no data and produce no results." />
      <div className="grid gap-4 md:grid-cols-2">
        {milestones.map((m) => (
          <Card key={m.id} className="p-4">
            <div className="flex items-center justify-between">
              <p className="text-[13px] font-semibold"><span className="mr-2 font-mono text-fg-3">{m.id}</span>{m.title}</p>
              <Badge tone={m.status === "Available" ? "success" : "neutral"}>{m.status}</Badge>
            </div>
            <ul className="mt-3 space-y-1 text-[13px] text-fg-2">
              {m.items.map((i) => <li key={i}>{i}</li>)}
            </ul>
          </Card>
        ))}
      </div>
    </>
  );
}
