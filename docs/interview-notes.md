# Interview Notes

## Demo Story

Start with the editable HTML prototype to show the full product shape: login, merchant menu, options, cart, checkout, simulated payment, order timeline, and Agent confirmation card. Then explain that the production implementation splits the same flow across Android, Spring Boot, and Python Agent Service.

Recommended walkthrough:

1. Register or log in.
2. Search a merchant and select dish options.
3. Add an address and validate the cart.
4. Create an order with an idempotency key.
5. Run simulated payment and show the delivery timeline.
6. Open the Agent and ask: `我感冒了，中午想吃清淡点，不要香菜，30 元以内，按默认地址帮我点。`
7. Show event cards, the confirmation card, amount limit, address, fees, discount, and cancellation/retry behavior.

## Engineering Points

- Android stays a client: XML UI, MVVM state, OkHttp, Glide, local ASR text input, no internal service tokens.
- Spring Boot is the authority for money, inventory, ownership, authorization, idempotency, and order state.
- Agent Service is constrained by LangGraph state and a fixed Spring Boot tool whitelist.
- Confirmation is deterministic. LLM output can classify intent, but cannot authorize order creation.
- Recovery uses workflow-level idempotency keys so uncertain order creation can retry without changing the logical operation.
- Tests use Fake LLM/Fake Tool Client and Fake Android API client to avoid real quota and real backend dependencies.

## Honest Limits

- Payment is simulated.
- There is no merchant, rider, admin, map, or real third-party payment integration.
- Local ASR is packaged for offline recognition after recording; it is not streaming transcription.
- The included file workflow store is for same-host process restart recovery. Multi-worker deployment needs a shared store and per `(userId, sessionId)` serialization.
- Screenshots/APK evidence must be generated from a configured Android SDK/Gradle environment; this task does not fake device screenshots.
