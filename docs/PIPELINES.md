# Pipeline Notes

This scaffold was generated for `cotor-project`.

## Default Workflow

- Pipeline name: `cotor-project-starter`
- Execution mode: `SEQUENTIAL`
- Agent: `codex`
- Stages:
  - `brief`: generate a short plan for the project
  - `refine`: turn the brief into an executable checklist

## Editing Guide

1. Update the stage prompts to match your task.
2. Change the execution mode if you need fan-out or review flows.
3. Add more stages once the project moves beyond the starter workflow.

## Validation Reminder

Run `cotor validate cotor-project-starter -c cotor.yaml` after changing the scaffold.