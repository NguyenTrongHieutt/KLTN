/** Skills as read-only pills (a pill means read-only status, DESIGN.md), in their order. */
export function SkillChips({ names }: { names: string[] }) {
  return (
    <ul className="flex flex-wrap gap-1.5">
      {names.map((name) => (
        <li key={name} className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold">
          {name}
        </li>
      ))}
    </ul>
  )
}
