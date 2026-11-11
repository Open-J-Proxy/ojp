# OJP Server SQL Injection Protection - Implementation Analysis

**Date:** November 11, 2026  
**Status:** Analysis outline; source investigation in progress  
**Scope:** Server-side defense in depth, not a runtime implementation

## Initial Direction

SQL injection prevention starts with application-side parameter binding.
A JDBC proxy receives SQL after application code has constructed it; it cannot
reliably reconstruct which characters came from trusted code and which came
from user input. Server-side inspection must therefore be described as a
configurable SQL policy boundary, not a guarantee that arbitrary application
SQL has been made safe.

The proposed direction to evaluate is an optional server-side SQL firewall:
observe first, then enforce explicit restrictions for selected workloads.
Do not rewrite SQL, interpolate parameters, or treat keyword matching as a
complete defense. Database least privilege and safe stored procedures remain
necessary.

## Investigation Plan

1. Trace SQL and parameters through the protocol, normal/prepared statements,
   batches, callable statements, resource dispatch, and XA execution.
2. Identify enforcement points and ordering relative to JDBC execution,
   SQL enhancement, resource allocation, errors, and cleanup.
3. Compare parser-based policies, query-template allowlists, and heuristics
   against OJP's dialect and compatibility requirements.
4. Define trusted policy scope, configuration, audit privacy, parser limits,
   cache correctness, operational modes, and rollout behavior.
5. Specify implementation work packages, integration coverage, performance
   evaluation, acceptance criteria, and decisions requiring maintainer input.

## Deliverable

The completed analysis will distinguish verified source behavior from proposed
changes, cite concrete extension points, and provide a phased recommendation.
No runtime protection is introduced by this document.
