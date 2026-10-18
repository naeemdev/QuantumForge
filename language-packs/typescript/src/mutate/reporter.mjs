// node:test reporter used by the mutation runner: one JSON line per finished test.
// Plain JavaScript so it loads without type stripping.
export default async function* reporter(source) {
  for await (const event of source) {
    if (event.type !== "test:pass" && event.type !== "test:fail") continue;
    if (event.data.details?.type === "suite" || event.data.skip || event.data.todo) continue;
    yield JSON.stringify({ file: event.data.file ?? "", name: event.data.name, passed: event.type === "test:pass" }) + "\n";
  }
}
