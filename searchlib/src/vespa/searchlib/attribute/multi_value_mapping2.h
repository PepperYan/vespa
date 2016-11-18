// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "multi_value_mapping2_base.h"
#include <vespa/searchlib/datastore/array_store.h>
#include "address_space.h"

namespace search {
namespace attribute {

/**
 * Class for mapping from from document id to an array of values.
 */
template <typename EntryT, typename RefT = datastore::EntryRefT<17> >
class MultiValueMapping2 : public MultiValueMapping2Base
{
public:
    using MultiValueType = EntryT;
private:
    using ArrayStore = datastore::ArrayStore<EntryT, RefT>;
    using generation_t = vespalib::GenerationHandler::generation_t;
    using ConstArrayRef = vespalib::ConstArrayRef<EntryT>;

    ArrayStore _store;
public:
    MultiValueMapping2(uint32_t maxSmallArraySize,
                       const GrowStrategy &gs = GrowStrategy());
    virtual ~MultiValueMapping2();
    ConstArrayRef get(uint32_t docId) const { return _store.get(_indices[docId]); }
    ConstArrayRef getDataForIdx(EntryRef idx) const { return _store.get(idx); }
    void set(uint32_t docId, ConstArrayRef values);

    // replace is generally unsafe and should only be used when
    // compacting enum store (replacing old enum index with updated enum index)
    void replace(uint32_t docId, ConstArrayRef values);

    // Pass on hold list management to underlying store
    void transferHoldLists(generation_t generation) { _store.transferHoldLists(generation); }
    void trimHoldLists(generation_t firstUsed) { _store.trimHoldLists(firstUsed); }
    template <class Reader>
    void prepareLoadFromMultiValue(Reader &) { }

    void compactWorst();

    // Following methods are not yet properly implemented.
    AddressSpace getAddressSpaceUsage() const { return AddressSpace(0, 0); }
    virtual MemoryUsage getMemoryUsage() const override { return MemoryUsage(); }
    virtual size_t getTotalValueCnt() const override { return 0; }

    // Mockups to temporarily silence code written for old multivalue mapping
    bool enoughCapacity(const Histogram &) { return true; }
    void performCompaction(Histogram &) { }
};

} // namespace search::attribute
} // namespace search