package com.xbot.xbot.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.xbot.xbot.R;
import com.xbot.xbot.data.FamilyRelation;
import com.xbot.xbot.data.PersonEntity;
import com.xbot.xbot.viewmodel.AppViewModel;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Bottom sheet listing enrolled people (mirrors Flutter {@code friend_list_sheet}). */
public class FriendListBottomSheet extends BottomSheetDialogFragment {
    private AppViewModel viewModel;
    private FriendAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_friend_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);
        adapter = new FriendAdapter();

        TextView countView = view.findViewById(R.id.friend_count);
        RecyclerView list = view.findViewById(R.id.friend_list);
        TextView emptyView = view.findViewById(R.id.friend_empty);

        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        adapter.setDeleteListener(person -> confirmDelete(person, () -> refreshList(countView, emptyView, list)));

        viewModel.getPeopleRevision().observe(getViewLifecycleOwner(), rev -> refreshList(countView, emptyView, list));
        refreshList(countView, emptyView, list);
    }

    private void refreshList(TextView countView, TextView emptyView, RecyclerView list) {
        List<PersonEntity> people = new ArrayList<>(viewModel.getPersonRepository().getPeople());
        adapter.setItems(people);
        countView.setText(getString(R.string.friend_count_fmt, people.size()));
        boolean empty = people.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void confirmDelete(PersonEntity person, Runnable onDeleted) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.friend_delete_title)
                .setMessage(getString(R.string.friend_delete_message, person.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.friend_delete_confirm, (d, w) ->
                        viewModel.deletePerson(person.id, () -> {
                            Toast.makeText(
                                    requireContext(),
                                    getString(R.string.friend_deleted_fmt, person.name),
                                    Toast.LENGTH_SHORT).show();
                            onDeleted.run();
                        }))
                .show();
    }

    private static final class FriendAdapter extends RecyclerView.Adapter<FriendViewHolder> {
        private final List<PersonEntity> items = new ArrayList<>();
        @Nullable private DeleteListener deleteListener;

        interface DeleteListener {
            void onDelete(PersonEntity person);
        }

        void setDeleteListener(@Nullable DeleteListener listener) {
            deleteListener = listener;
        }

        void setItems(List<PersonEntity> people) {
            items.clear();
            items.addAll(people);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public FriendViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_friend, parent, false);
            return new FriendViewHolder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull FriendViewHolder holder, int position) {
            holder.bind(items.get(position), deleteListener);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class FriendViewHolder extends RecyclerView.ViewHolder {
        private final TextView avatarView;
        private final TextView nameView;
        private final TextView subtitleView;
        private final ImageButton deleteButton;

        FriendViewHolder(@NonNull View itemView) {
            super(itemView);
            avatarView = itemView.findViewById(R.id.avatar);
            nameView = itemView.findViewById(R.id.name);
            subtitleView = itemView.findViewById(R.id.subtitle);
            deleteButton = itemView.findViewById(R.id.btn_delete);
        }

        void bind(PersonEntity person, @Nullable FriendAdapter.DeleteListener listener) {
            nameView.setText(person.name);
            FamilyRelation relation = FamilyRelation.fromKey(person.relation);
            subtitleView.setText(itemView.getContext().getString(
                    R.string.friend_item_subtitle, relation.label, person.getSampleCount()));

            String avatarPath = person.avatarPath;
            Bitmap avatar = null;
            if (avatarPath != null) {
                File file = new File(avatarPath);
                if (file.exists()) {
                    avatar = BitmapFactory.decodeFile(file.getAbsolutePath());
                }
            }
            if (avatar != null) {
                avatarView.setBackground(new android.graphics.drawable.BitmapDrawable(
                        itemView.getResources(), avatar));
                avatarView.setText("");
            } else {
                avatarView.setBackgroundResource(R.drawable.bg_avatar_placeholder);
                String initial = person.name != null && !person.name.isEmpty()
                        ? person.name.substring(0, 1)
                        : "?";
                avatarView.setText(initial);
            }

            deleteButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDelete(person);
                }
            });
        }
    }
}
