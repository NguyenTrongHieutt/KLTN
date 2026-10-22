/** The busy marker's query; "Nghiệm thu giai đoạn" refreshes it so JOB-H's banner shows at once. */
export const busyKey = (jobId: string) => ['job', 'busy', jobId] as const
