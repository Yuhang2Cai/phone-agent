# Architecture

饭搭子 AI has three runtime layers.

## Android

The Android app uses XML layouts, MVVM-style `ViewModel` state, OkHttp, Glide, and Kotlin coroutines. `ApiClient` centralizes Bearer auth, timeout handling, JSON error mapping, and one retry for idempotent GET requests. Glide is wrapped behind an image loader so image failures do not block text content.

The Agent UI is a bottom sheet with half-screen and full-screen modes. It exposes messages, event cards, candidate cart, confirmation card, authorization mode, and retryable errors from `AgentViewModel`. The floating entry point is limited to ordering surfaces; auth and payment screens intentionally hide it.

Offline ASR is scoped as local text input. The model-size tradeoff is to package an INT8 Paraformer/Sherpa-ONNX model with the APK, recognize after recording ends, and let the user edit the recognized text before sending. No LLM or LangGraph code runs on Android.

## Spring Boot

Spring Boot owns user identity, menu data, addresses, cart validation, orders, simulated payment, delivery timeline, Agent Gateway, internal tool authorization, and idempotency. It is the final authority for price, stock, address ownership, amount limits, order state, and automation grants.

SSE events are persisted and streamed by Spring Boot. The client can reconnect with `Last-Event-ID`; public Agent messages and event streams use the normal user Bearer token. Internal tool calls require the configured internal token and are not exposed to Android.

## Python Agent Service

Python hosts the single `FoodOrderingAgent`. LangChain is used for model adaptation and structured intent output. LangGraph is used for the actual workflow: parse intent, collect missing info, search candidates, build cart, validate order, wait for confirmation, create order, and payment follow-up.

The Agent never connects to MySQL and never performs business mutations directly. All side effects go through Spring Boot tools with stable workflow-level idempotency keys. Confirmation, order recovery, payment retry, and cancellation states can be exported/restored with signed workflow snapshots. A durable workflow store can be enabled for same-host restart recovery.

## Why One Agent

The first version uses one constrained food-ordering Agent instead of multiple agents. That keeps the audit boundary small: one workflow, one tool whitelist, one confirmation protocol, and Spring Boot as the single business authority. This is easier to test and safer for a demo involving order creation.
