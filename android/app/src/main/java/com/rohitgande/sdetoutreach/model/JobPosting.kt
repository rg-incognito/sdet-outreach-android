package com.rohitgande.sdetoutreach.model

// Mirrors src/types.ts's JobPosting (Node tool). `title` intentionally holds the full
// post body, not a short label — LinkedIn content-search posts have no separate title
// field, and filters match against the whole body (see linkedin.ts).
data class JobPosting(
    val id: String,
    val url: String,
    val title: String,
    val company: String,
    val descriptionText: String,
    val scrapedAt: String,
)

data class FilteredJob(
    val job: JobPosting,
    val recruiterEmail: String,
)
