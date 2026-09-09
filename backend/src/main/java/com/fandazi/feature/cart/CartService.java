package com.fandazi.feature.cart;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.DishOptionRepository;
import com.fandazi.feature.catalog.DishRepository;
import com.fandazi.feature.catalog.MerchantRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {
    private static final int MAX_ITEM_QUANTITY = 100;
    private static final long MAX_DISH_QUANTITY = 1_000L;
    private final CartDraftRepository drafts;
    private final MerchantRepository merchants;
    private final DishRepository dishes;
    private final DishOptionRepository options;
    private final UserRepository users;

    public CartService(CartDraftRepository drafts, MerchantRepository merchants, DishRepository dishes,
                       DishOptionRepository options, UserRepository users) {
        this.drafts = drafts;
        this.merchants = merchants;
        this.dishes = dishes;
        this.options = options;
        this.users = users;
    }

    @Transactional
    public CartDraft createDraft(Long userId, Long merchantId) {
        var user = users.findById(userId).orElseThrow(() -> new DomainException("用户不存在"));
        var merchant = merchants.findById(merchantId).orElseThrow(() -> new DomainException("商家不存在"));
        return drafts.save(new CartDraft(user, merchant));
    }

    @Transactional
    public CartDraft replaceItems(Long userId, Long draftId, List<CartItemCommand> commands) {
        var draft = owned(userId, draftId);
        if (commands == null || commands.isEmpty()) {
            throw new DomainException("购物车不能为空");
        }
        List<CartDraftItem> items = new ArrayList<>();
        for (var command : commands) {
            if (command.quantity() <= 0 || command.quantity() > MAX_ITEM_QUANTITY) {
                throw new DomainException("数量无效");
            }
            var dish = dishes.findById(command.dishId()).orElseThrow(() -> new DomainException("菜品不存在"));
            if (!dish.getMerchant().getId().equals(draft.getMerchant().getId())) {
                throw new DomainException("菜品不属于商家");
            }
            items.add(new CartDraftItem(draft, dish, command.quantity(), command.optionIds()));
        }
        draft.replaceItems(items);
        return draft;
    }

    @Transactional(readOnly = true)
    public CartQuote validate(Long userId, Long draftId) {
        return quote(userId, draftId, false);
    }

    @Transactional
    public CartQuote reserveAndQuote(Long userId, Long draftId) {
        return quote(userId, draftId, true);
    }

    private CartQuote quote(Long userId, Long draftId, boolean reserve) {
        var draft = owned(userId, draftId);
        if (!draft.getMerchant().isOpen()) {
            throw new DomainException("商家未营业");
        }

        List<CartQuote.Line> lines = new ArrayList<>();
        Map<Long, Long> requestedStock = new LinkedHashMap<>();
        Map<Long, Integer> availableStock = new LinkedHashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (var item : draft.getItems()) {
            var dish = dishes.findById(item.getDish().getId()).orElseThrow(() -> new DomainException("菜品不存在"));
            if (!dish.isListed()) {
                throw new DomainException("菜品已下架");
            }
            long requested = Math.addExact(requestedStock.getOrDefault(dish.getId(), 0L), item.getQuantity());
            if (requested > MAX_DISH_QUANTITY) {
                throw new DomainException("数量超出上限");
            }
            requestedStock.put(dish.getId(), requested);
            availableStock.putIfAbsent(dish.getId(), dish.getStock());

            BigDecimal optionDelta = BigDecimal.ZERO;
            List<Long> optionIds = item.getOptionIds();
            List<String> summaries = new ArrayList<>();
            for (Long optionId : optionIds) {
                var option = options.findById(optionId).orElseThrow(() -> new DomainException("规格不存在"));
                if (!option.getDish().getId().equals(dish.getId()) || !option.isAvailable()) {
                    throw new DomainException("规格无效");
                }
                optionDelta = optionDelta.add(option.getPriceDelta());
                summaries.add(option.getGroupName() + ":" + option.getOptionName());
            }
            BigDecimal unitPrice = dish.getPrice().add(optionDelta);
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
            subtotal = subtotal.add(lineTotal);
            lines.add(new CartQuote.Line(dish.getId(), dish.getName(), item.getQuantity(), List.copyOf(optionIds),
                    String.join(",", summaries), unitPrice, lineTotal));
        }

        for (var request : requestedStock.entrySet()) {
            if (availableStock.get(request.getKey()) < request.getValue()) {
                throw new DomainException("库存不足");
            }
        }
        if (subtotal.compareTo(draft.getMerchant().getMinimumOrder()) < 0) {
            throw new DomainException("未达到起送价");
        }
        if (reserve) {
            for (var request : requestedStock.entrySet()) {
                if (dishes.decrementStockIfAvailable(request.getKey(), Math.toIntExact(request.getValue())) != 1) {
                    throw new DomainException("库存不足");
                }
            }
        }

        BigDecimal fee = draft.getMerchant().getDeliveryFee();
        return new CartQuote(draft.getMerchant().getId(), List.copyOf(lines), subtotal, fee,
                BigDecimal.ZERO, subtotal.add(fee));
    }

    public CartDraft owned(Long userId, Long id) {
        return drafts.findByIdAndUserId(id, userId).orElseThrow(() -> new DomainException("购物车不存在"));
    }
}
