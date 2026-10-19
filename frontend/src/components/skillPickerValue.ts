/** A whole Skill Category among the Skill picker's values, in its `wholeCategories` mode (FR-72, AD-38). */
const CATEGORY = 'category:'

export function categoryValue(categoryId: string): string {
  return `${CATEGORY}${categoryId}`
}

/** The picker's values split into Skills and whole Categories. */
export function splitSkillPickerValue(values: string[]): { skills: string[]; categories: string[] } {
  return {
    skills: values.filter((value) => !value.startsWith(CATEGORY)),
    categories: values.filter((value) => value.startsWith(CATEGORY)).map((value) => value.slice(CATEGORY.length)),
  }
}
